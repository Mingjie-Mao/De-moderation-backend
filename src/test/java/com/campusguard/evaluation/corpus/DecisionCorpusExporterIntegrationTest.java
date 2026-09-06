package com.campusguard.evaluation.corpus;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusguard.AbstractIntegrationTest;
import com.campusguard.appeal.AppealDecision;
import com.campusguard.appeal.AppealDecisionRequest;
import com.campusguard.appeal.AppealService;
import com.campusguard.appeal.CreateAppealRequest;
import com.campusguard.common.TargetType;
import com.campusguard.moderation.FinalAction;
import com.campusguard.moderation.ModerationCase;
import com.campusguard.moderation.ModerationCaseRepository;
import com.campusguard.moderation.ModerationDecision;
import com.campusguard.moderation.admin.AdminModerationService;
import com.campusguard.moderation.admin.CaseDecisionRequest;
import com.campusguard.moderation.engine.ModerationVerdict;
import com.campusguard.post.Post;
import com.campusguard.post.PostRepository;
import com.campusguard.user.User;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Harvesting decisions that have already been made.
 *
 * <p>The value of this corpus is not that it has rows; it is that the rows say
 * which decisions a person reconsidered, which ones overruled the engine, and
 * how much history the author had at the time. Each of those has a way of being
 * quietly wrong that still produces a plausible-looking file, so each is pinned.
 */
class DecisionCorpusExporterIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private DecisionCorpusExporter exporter;

    @Autowired
    private ModerationCaseRepository cases;

    @Autowired
    private PostRepository posts;

    @Autowired
    private AdminModerationService moderation;

    @Autowired
    private AppealService appeals;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void carriesTheContentTheEngineAndThePersonInOneRow() {
        User author = newUser();
        User admin = newAdmin();
        UUID caseId = awaitingReviewCase(author, "You are an idiot.", ModerationDecision.REMOVE, "ABUSE");

        moderation.decide(admin.getId(), caseId, new CaseDecisionRequest(FinalAction.HIDE, "Clear attack."));

        DecisionSample sample = find(caseId);

        assertThat(sample.body()).isEqualTo("You are an idiot.");
        assertThat(sample.engineDecision()).isEqualTo(ModerationDecision.REMOVE);
        assertThat(sample.ruleCodes()).containsExactly("ABUSE");
        assertThat(sample.finalAction()).isEqualTo(FinalAction.HIDE);
        assertThat(sample.label()).isEqualTo(ModerationDecision.REMOVE);
        assertThat(sample.engineAgreed()).isTrue();
        assertThat(sample.basis()).isEqualTo(LabelBasis.ROUTINE);
    }

    /**
     * The rows worth the most are the ones where a person did something other
     * than what the model suggested. A corpus that could not tell those apart
     * would be a corpus of the model agreeing with itself.
     */
    @Test
    void marksTheRowsWhereAPersonOverruledTheEngine() {
        User author = newUser();
        User admin = newAdmin();
        UUID caseId = awaitingReviewCase(
                author, "Selling my old textbooks, DM me.", ModerationDecision.REMOVE, "SPAM");

        moderation.decide(admin.getId(), caseId, new CaseDecisionRequest(FinalAction.NONE, "Ordinary student sale."));

        DecisionSample sample = find(caseId);

        assertThat(sample.label()).isEqualTo(ModerationDecision.ALLOW);
        assertThat(sample.engineAgreed()).isFalse();
        assertThat(sample.basis()).isEqualTo(LabelBasis.CORRECTION);
    }

    @Test
    void recordsThatADecisionWasRevised() {
        User author = newUser();
        User admin = newAdmin();
        UUID caseId = awaitingReviewCase(author, "Get out of here.", ModerationDecision.REMOVE, "ABUSE");

        moderation.decide(admin.getId(), caseId, new CaseDecisionRequest(FinalAction.HIDE, "Attack."));
        moderation.decide(admin.getId(), caseId, new CaseDecisionRequest(FinalAction.NONE, "On reflection, banter."));

        DecisionSample sample = find(caseId);

        assertThat(sample.basis()).isEqualTo(LabelBasis.REVISED);
        assertThat(sample.hard()).isTrue();
        assertThat(sample.label()).isEqualTo(ModerationDecision.ALLOW);
    }

    /**
     * The strongest row this system can produce: the author said the decision was
     * wrong and a reviewer agreed with them.
     */
    @Test
    void recordsAnOverturnedAppealAsTheStrongestBasis() {
        User author = newUser();
        User admin = newAdmin();
        UUID caseId = awaitingReviewCase(author, "Nobody can stand you.", ModerationDecision.REMOVE, "ABUSE");

        moderation.decide(admin.getId(), caseId, new CaseDecisionRequest(FinalAction.HIDE, "Attack."));
        appeals.create(author.getId(), new CreateAppealRequest(caseId, "It was about a football team."));

        UUID appealId = appeals.mine(author.getId(), 5).getFirst().id();
        appeals.decide(admin.getId(), appealId, new AppealDecisionRequest(AppealDecision.OVERTURN, "Fair point."));

        DecisionSample sample = find(caseId);

        assertThat(sample.basis()).isEqualTo(LabelBasis.APPEAL_OVERTURNED);
        assertThat(sample.hard()).isTrue();
    }

    /**
     * Counted as of the decision, not as of the export.
     *
     * <p>A row that said "no priors" when it was decided and "two priors" when it
     * is read describes two different situations, and a model trained on the
     * second would be learning from a future it could not have seen.
     */
    @Test
    void countsPriorCasesAsTheyStoodWhenTheDecisionWasMade() {
        User author = newUser();
        User admin = newAdmin();

        UUID first = awaitingReviewCase(author, "First offence.", ModerationDecision.REMOVE, "ABUSE");
        moderation.decide(admin.getId(), first, new CaseDecisionRequest(FinalAction.HIDE, "One."));

        UUID second = awaitingReviewCase(author, "Second offence.", ModerationDecision.REMOVE, "ABUSE");
        moderation.decide(admin.getId(), second, new CaseDecisionRequest(FinalAction.HIDE, "Two."));

        assertThat(find(first).priorResolvedCases()).isZero();
        assertThat(find(second).priorResolvedCases()).isEqualTo(1);
    }

    /**
     * The same person is recognisable across rows without the corpus carrying who
     * they are. Repeat-offender structure is most of what makes this data worth
     * having, and it survives the hashing.
     */
    @Test
    void identifiesAnAuthorWithoutNamingThem() {
        User author = newUser();
        User admin = newAdmin();

        UUID first = awaitingReviewCase(author, "One.", ModerationDecision.REMOVE, "ABUSE");
        UUID second = awaitingReviewCase(author, "Two.", ModerationDecision.REMOVE, "ABUSE");
        moderation.decide(admin.getId(), first, new CaseDecisionRequest(FinalAction.HIDE, "a"));
        moderation.decide(admin.getId(), second, new CaseDecisionRequest(FinalAction.HIDE, "b"));

        DecisionSample one = find(first);
        DecisionSample two = find(second);

        assertThat(one.authorKey()).isEqualTo(two.authorKey());
        assertThat(one.authorKey())
                .doesNotContain(author.getUsername())
                .doesNotContain(author.getId().toString());
    }

    /** A case still awaiting review is not a decision, and must not become a labelled row. */
    @Test
    void leavesOutCasesNobodyHasDecided() {
        UUID caseId = awaitingReviewCase(newUser(), "Undecided.", ModerationDecision.REMOVE, "ABUSE");

        assertThat(exporter.export(5000))
                .extracting(DecisionSample::caseId)
                .doesNotContain(caseId.toString());
    }

    private DecisionSample find(UUID caseId) {
        return exporter.export(5000).stream()
                .filter(sample -> sample.caseId().equals(caseId.toString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Case " + caseId + " is missing from the corpus."));
    }

    private UUID awaitingReviewCase(User author, String body, ModerationDecision decision, String ruleCode) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            Post post = posts.saveAndFlush(new Post(uniqueForumKey(), author, "A title", body));

            cases.openCaseIfAbsent(TargetType.POST.name(), post.getId());
            UUID caseId = cases.findOpenCaseId(TargetType.POST, post.getId()).orElseThrow();

            ModerationCase moderationCase = cases.findById(caseId).orElseThrow();
            moderationCase.markAnalysing();
            moderationCase.recordVerdict(
                    "keyword-v1",
                    new ModerationVerdict(decision, 0.8, "Matched a configured term.", List.of(ruleCode)));

            return cases.saveAndFlush(moderationCase).getId();
        });
    }
}
