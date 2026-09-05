package com.campusguard.moderation;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusguard.AbstractIntegrationTest;
import com.campusguard.comment.Comment;
import com.campusguard.comment.CommentRepository;
import com.campusguard.common.TargetType;
import com.campusguard.moderation.engine.ModerationVerdict;
import com.campusguard.post.Post;
import com.campusguard.post.PostRepository;
import com.campusguard.user.User;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Getting from a person to what has been decided about them.
 *
 * <p>Cases are keyed by the content they concern, so this join has never existed:
 * a reviewer deciding whether a third offence deserves more than the first two
 * had no way to learn there had been two. These queries are that path, and they
 * are worth their own tests because each one has a way of being quietly wrong
 * that still returns rows — a history missing its removed content, a precedent
 * list padded with reports somebody dismissed.
 *
 * <p>Runs against the real PostgreSQL for the usual reason, plus one specific to
 * this change: {@code findResolvedByRuleCode} is native and leans on JSONB
 * containment, which no in-memory database implements the same way.
 */
class CaseHistoryQueryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ModerationCaseRepository cases;

    @Autowired
    private ContentLocator contentLocator;

    @Autowired
    private PostRepository posts;

    @Autowired
    private CommentRepository comments;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void findsAnAuthorsHistoryAcrossBothKindsOfContent() {
        User author = newUser();
        Post post = posts.saveAndFlush(new Post(uniqueForumKey(), author, "A title", "A body"));
        Comment comment = comments.saveAndFlush(new Comment(post, null, author, "A comment"));

        UUID postCase = resolvedCase(TargetType.POST, post.getId(), "ABUSE", FinalAction.HIDE);
        UUID commentCase = resolvedCase(TargetType.COMMENT, comment.getId(), "SPAM", FinalAction.HIDE);

        List<ContentLocator.TargetRef> targets = contentLocator.targetsOf(author.getId());
        assertThat(targets).hasSize(2);

        List<ModerationCase> history = cases.findResolvedForTargets(
                targets.stream().map(ContentLocator.TargetRef::id).toList(),
                Instant.now().minus(90, ChronoUnit.DAYS));

        assertThat(history).extracting(ModerationCase::getId)
                .containsExactlyInAnyOrder(postCase, commentCase);
    }

    /**
     * The whole point of a history is the content that is no longer there. An
     * author whose offending posts were all hidden would otherwise read as
     * someone with a clean record.
     */
    @Test
    void keepsRemovedContentInTheHistory() {
        User author = newUser();
        Post post = posts.saveAndFlush(new Post(uniqueForumKey(), author, "A title", "A body"));
        UUID caseId = resolvedCase(TargetType.POST, post.getId(), "ABUSE", FinalAction.HIDE);

        post.softDelete(Instant.now());
        posts.saveAndFlush(post);

        List<UUID> targetIds =
                contentLocator.targetsOf(author.getId()).stream().map(ContentLocator.TargetRef::id).toList();

        assertThat(cases.findResolvedForTargets(targetIds, Instant.now().minus(90, ChronoUnit.DAYS)))
                .extracting(ModerationCase::getId)
                .containsExactly(caseId);
    }

    @Test
    void leavesOutDecisionsOlderThanTheWindow() {
        User author = newUser();
        Post recent = posts.saveAndFlush(new Post(uniqueForumKey(), author, "Recent", "body"));
        Post ancient = posts.saveAndFlush(new Post(uniqueForumKey(), author, "Ancient", "body"));

        UUID recentCase = resolvedCase(TargetType.POST, recent.getId(), "ABUSE", FinalAction.HIDE);
        UUID ancientCase = resolvedCase(TargetType.POST, ancient.getId(), "ABUSE", FinalAction.HIDE);
        backdateDecision(ancientCase, Instant.now().minus(200, ChronoUnit.DAYS));

        List<UUID> targetIds =
                contentLocator.targetsOf(author.getId()).stream().map(ContentLocator.TargetRef::id).toList();

        List<ModerationCase> history =
                cases.findResolvedForTargets(targetIds, Instant.now().minus(90, ChronoUnit.DAYS));

        assertThat(history).extracting(ModerationCase::getId).containsExactly(recentCase);
        assertThat(history).extracting(ModerationCase::getId).doesNotContain(ancientCase);
    }

    /**
     * A case can carry several codes, and the one being asked about is rarely the
     * only one. Containment against a bare string is what makes that work without
     * building an array literal at the call site; if it ever stops working this
     * returns nothing rather than failing loudly, which is why it is pinned.
     */
    @Test
    void findsPrecedentByOneCodeAmongSeveral() {
        String code = uniqueRuleCode();
        User author = newUser();
        Post post = posts.saveAndFlush(new Post(uniqueForumKey(), author, "A title", "A body"));

        UUID caseId = resolvedCase(TargetType.POST, post.getId(), List.of("SPAM", code), FinalAction.HIDE);

        assertThat(cases.findResolvedByRuleCode(code, 5))
                .extracting(ModerationCase::getId)
                .containsExactly(caseId);
    }

    /**
     * A case closed with NONE is a reviewer saying the report was wrong. Reading
     * it as precedent would teach the opposite of what happened.
     */
    @Test
    void leavesDismissedReportsOutOfPrecedent() {
        String code = uniqueRuleCode();
        User author = newUser();
        Post acted = posts.saveAndFlush(new Post(uniqueForumKey(), author, "Acted", "body"));
        Post dismissed = posts.saveAndFlush(new Post(uniqueForumKey(), author, "Dismissed", "body"));

        UUID actedCase = resolvedCase(TargetType.POST, acted.getId(), code, FinalAction.HIDE);
        resolvedCase(TargetType.POST, dismissed.getId(), code, FinalAction.NONE);

        assertThat(cases.findResolvedByRuleCode(code, 5))
                .extracting(ModerationCase::getId)
                .containsExactly(actedCase);
    }

    @Test
    void honoursTheRowLimit() {
        String code = uniqueRuleCode();
        User author = newUser();

        for (int i = 0; i < 4; i++) {
            Post post = posts.saveAndFlush(new Post(uniqueForumKey(), author, "Title " + i, "body"));
            resolvedCase(TargetType.POST, post.getId(), code, FinalAction.HIDE);
        }

        assertThat(cases.findResolvedByRuleCode(code, 2)).hasSize(2);
    }

    /**
     * Asserts the indexes exist, not that the planner uses them.
     *
     * <p>With a handful of rows a sequential scan is genuinely the cheaper plan,
     * so an {@code EXPLAIN} assertion here would either fail or have to be
     * written loosely enough to pass on any plan at all. What can be checked is
     * that the migration created what the queries were written against, and that
     * the partial predicates still match: a predicate that drifts away from its
     * query silently costs the index rather than breaking anything.
     */
    @Test
    void migrationCreatesTheIndexesTheseQueriesWereWrittenFor() {
        List<String> definitions = jdbcTemplate.queryForList(
                "select indexdef from pg_indexes where tablename = 'moderation_cases' and indexname = ?",
                String.class,
                "idx_moderation_cases_rule_codes");

        assertThat(definitions).hasSize(1);
        assertThat(definitions.getFirst())
                .contains("USING gin")
                .contains("jsonb_path_ops")
                .contains("final_action)::text <> 'NONE'");

        assertThat(jdbcTemplate.queryForList(
                        "select indexdef from pg_indexes where tablename = 'moderation_cases' and indexname = ?",
                        String.class,
                        "idx_moderation_cases_target_resolved"))
                .hasSize(1);
    }

    /**
     * A code no other test uses.
     *
     * <p>{@code findResolvedByRuleCode} deliberately has no author or forum
     * filter — precedent is global — so it is the one query here that would
     * otherwise see every other test's cases. Isolation has to come from the code
     * itself. It never reaches {@code VerdictParser}, which is the component that
     * would reject an unknown one.
     */
    private String uniqueRuleCode() {
        return "T" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private UUID resolvedCase(TargetType type, UUID targetId, String ruleCode, FinalAction action) {
        return resolvedCase(type, targetId, List.of(ruleCode), action);
    }

    /**
     * Drives a case through the real state machine rather than inserting a row in
     * its final shape, so these tests keep agreeing with the workflow that
     * produces the rows in production.
     */
    private UUID resolvedCase(TargetType type, UUID targetId, List<String> ruleCodes, FinalAction action) {
        User admin = newAdmin();

        // In its own transaction because openCaseIfAbsent flushes, and because
        // the queries under test have to see committed rows: a test method that
        // held one transaction open around both the writing and the reading would
        // not be exercising the same visibility production does.
        return new TransactionTemplate(transactionManager).execute(status -> {
            cases.openCaseIfAbsent(type.name(), targetId);
            UUID caseId = cases.findOpenCaseId(type, targetId).orElseThrow();

            ModerationCase moderationCase = cases.findById(caseId).orElseThrow();
            moderationCase.markAnalysing();
            moderationCase.recordVerdict(
                    "test-engine",
                    new ModerationVerdict(ModerationDecision.REMOVE, 0.9, "Recorded by a test.", ruleCodes));
            moderationCase.resolve(admin, action);

            return cases.saveAndFlush(moderationCase).getId();
        });
    }

    /** {@code resolve} stamps the clock itself, so a test that needs an old decision has to say so directly. */
    private void backdateDecision(UUID caseId, Instant decidedAt) {
        jdbcTemplate.update(
                "update moderation_cases set decided_at = ? where id = ?",
                java.sql.Timestamp.from(decidedAt),
                caseId);
    }
}
