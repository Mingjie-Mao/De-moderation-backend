package com.campusguard.evaluation.investigation;

import com.campusguard.common.TargetType;
import com.campusguard.moderation.FinalAction;
import com.campusguard.moderation.ModerationCase;
import com.campusguard.moderation.ModerationCaseRepository;
import com.campusguard.moderation.ModerationDecision;
import com.campusguard.moderation.engine.ModerationVerdict;
import com.campusguard.post.Post;
import com.campusguard.post.PostRepository;
import com.campusguard.user.User;
import com.campusguard.user.UserRepository;
import com.campusguard.user.UserRole;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the situation a scenario describes, so an investigation can be asked
 * about it.
 *
 * <p>Writes. That is why the benchmark that uses this is a test against a
 * throwaway database rather than a command like {@code EvaluationCommand}, which
 * only reads and can therefore be pointed at anything. Running this against a
 * real forum would invent moderation history for real people.
 *
 * <p>Precedent is seeded immediately before the case under test, and never
 * earlier. The precedent query is global and ordered by decision time, so
 * whatever was written last is what the assistant will be shown; building all
 * the fixtures up front would let one scenario's precedent answer another's
 * question.
 */
@Service
public class ScenarioFixture {

    private final ModerationCaseRepository cases;
    private final PostRepository posts;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;

    public ScenarioFixture(
            ModerationCaseRepository cases,
            PostRepository posts,
            UserRepository users,
            PasswordEncoder passwordEncoder,
            JdbcTemplate jdbcTemplate) {
        this.cases = cases;
        this.posts = posts;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param caseId the case an investigation will be asked about
     * @param priorCaseIds this author's earlier decisions
     * @param precedentCaseIds the cases the precedent lookup will return
     */
    public record Built(UUID caseId, Set<UUID> priorCaseIds, Set<UUID> precedentCaseIds) {

        /** What a brief has to cite for this scenario to count as grounded. */
        public Set<UUID> required(InvestigationScenario.MustCite mustCite) {
            return switch (mustCite) {
                case NOTHING -> Set.of();
                case PRIORS -> priorCaseIds;
                case PRECEDENT -> precedentCaseIds;
                case BOTH -> {
                    Set<UUID> both = new LinkedHashSet<>(priorCaseIds);
                    both.addAll(precedentCaseIds);
                    yield both;
                }
            };
        }
    }

    @Transactional
    public Built build(InvestigationScenario scenario) {
        User author = newUser();
        User moderator = newModerator();

        retireEarlierSeeds(scenario.ruleCode());

        Set<UUID> priors = new LinkedHashSet<>();
        for (int i = 0; i < scenario.priorActions().size(); i++) {
            UUID priorCase = resolvedCase(
                    author, moderator, "An earlier post by this author.",
                    scenario.ruleCode(), scenario.priorActions().get(i));

            // Backdated, because the ninety-day window is one of the things a
            // scenario can be about and resolve() always stamps the clock now.
            int ageDays = i < scenario.priorAgeDays().size() ? scenario.priorAgeDays().get(i) : 7;
            backdate(priorCase, Instant.now().minus(ageDays, ChronoUnit.DAYS));

            priors.add(priorCase);
        }

        Set<UUID> precedent = new LinkedHashSet<>();
        for (FinalAction action : scenario.precedentActions()) {
            precedent.add(resolvedCase(
                    newUser(), moderator, "A past case under this rule.", scenario.ruleCode(), action));
        }

        // Last, and it matters that it is last: precedent is queried most-recent
        // first, so anything written after this point would be shown instead of
        // the precedent this scenario is about.
        UUID caseId = openCase(author, scenario);

        return new Built(caseId, priors, precedent);
    }

    /** The case under test: analysed, awaiting a person, which is the only state an investigation runs in. */
    private UUID openCase(User author, InvestigationScenario scenario) {
        Post post = posts.saveAndFlush(new Post(forumKey(), author, scenario.title(), scenario.body()));

        cases.openCaseIfAbsent(TargetType.POST.name(), post.getId());
        UUID caseId = cases.findOpenCaseId(TargetType.POST, post.getId()).orElseThrow();

        ModerationCase moderationCase = cases.findById(caseId).orElseThrow();
        moderationCase.markAnalysing();
        moderationCase.recordVerdict(
                "keyword-v1",
                new ModerationVerdict(
                        scenario.engineDecision() == null ? ModerationDecision.REMOVE : scenario.engineDecision(),
                        0.8,
                        "Flagged for review.",
                        scenario.ruleCode() == null ? List.of() : List.of(scenario.ruleCode())));

        return cases.saveAndFlush(moderationCase).getId();
    }

    private UUID resolvedCase(User author, User moderator, String body, String ruleCode, FinalAction action) {
        Post post = posts.saveAndFlush(new Post(forumKey(), author, "Earlier", body));

        cases.openCaseIfAbsent(TargetType.POST.name(), post.getId());
        UUID caseId = cases.findOpenCaseId(TargetType.POST, post.getId()).orElseThrow();

        ModerationCase moderationCase = cases.findById(caseId).orElseThrow();
        moderationCase.markAnalysing();
        moderationCase.recordVerdict(
                "keyword-v1",
                new ModerationVerdict(
                        ModerationDecision.REMOVE, 0.8, "Matched a configured term.",
                        ruleCode == null ? List.of() : List.of(ruleCode)));
        moderationCase.resolve(moderator, action);

        return cases.saveAndFlush(moderationCase).getId();
    }

    /**
     * Pushes every earlier case under this rule out of the ninety-day window.
     *
     * <p>Precedent and the dismissal rate are both global to a rule code, so
     * without this a scenario is scored against whatever earlier scenarios in the
     * same run happened to seed. The precedent list survived that by accident — it
     * takes only the five most recent and each scenario seeds just before it runs
     * — but the dismissal rate counts everything in the window, so it was
     * measuring the benchmark's own history rather than the scenario's.
     *
     * <p>Aged out rather than deleted: they are still real rows with real audit
     * trails, and a scenario that wants an old record can still make one.
     */
    private void retireEarlierSeeds(String ruleCode) {
        if (ruleCode == null) {
            return;
        }

        jdbcTemplate.update(
                "update moderation_cases set decided_at = ? "
                        + "where status = 'RESOLVED' and decided_at is not null "
                        + "and rule_codes @> to_jsonb(cast(? as text))",
                Timestamp.from(Instant.now().minus(400, ChronoUnit.DAYS)),
                ruleCode);
    }

    private void backdate(UUID caseId, Instant decidedAt) {
        jdbcTemplate.update(
                "update moderation_cases set decided_at = ? where id = ?", Timestamp.from(decidedAt), caseId);
    }

    private User newUser() {
        return users.saveAndFlush(new User(
                "scenario_" + UUID.randomUUID().toString().substring(0, 12),
                passwordEncoder.encode("scenario-fixture-password"),
                UserRole.MEMBER));
    }

    private User newModerator() {
        return users.saveAndFlush(new User(
                "scenario_mod_" + UUID.randomUUID().toString().substring(0, 8),
                passwordEncoder.encode("scenario-fixture-password"),
                UserRole.ADMIN));
    }

    /** A forum per scenario, so nothing here can turn up in another one's feed. */
    private String forumKey() {
        return "scenario_" + UUID.randomUUID().toString().substring(0, 8);
    }
}
