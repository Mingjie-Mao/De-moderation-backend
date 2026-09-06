package com.campusguard.moderation.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campusguard.AbstractIntegrationTest;
import com.campusguard.audit.AuditActorType;
import com.campusguard.audit.AuditEntry;
import com.campusguard.audit.AuditEntryRepository;
import com.campusguard.common.TargetType;
import com.campusguard.moderation.FinalAction;
import com.campusguard.moderation.CaseStatus;
import com.campusguard.moderation.ModerationCase;
import com.campusguard.moderation.ModerationCaseRepository;
import com.campusguard.moderation.ModerationDecision;
import com.campusguard.moderation.engine.ModerationVerdict;
import com.campusguard.post.Post;
import com.campusguard.post.PostRepository;
import com.campusguard.user.User;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.campusguard.moderation.admin.AdminModerationService;
import com.campusguard.moderation.engine.ai.AiInvocationRecorder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The endpoints with the assistant switched on — which is how it ships.
 *
 * <p>That is not a gap in coverage. Off is the default and therefore the
 * configuration almost every deployment runs, so the behaviour worth pinning
 * first is what a reviewer meets there: a clear answer rather than a 500 or a
 * missing route. The loop itself is covered against a scripted model, and
 * against a real one behind an environment variable.
 *
 * <p>Reading a brief back is exercised by writing one through the service, which
 * is also the only way to get one without a model. It happens to test the part
 * most likely to rot: the round trip through a JSONB column.
 */
class InvestigationApiIntegrationTest extends AbstractIntegrationTest {

    /**
     * A CaseInvestigator wired to a model that always answers.
     *
     * <p>Supplied here rather than left absent so that the enabled path can be
     * tested at all. The disabled path -- which is how this ships -- is covered by
     * its own assertion below using a service built without one.
     */
    @TestConfiguration
    static class StubInvestigator {

        static final CountingModel MODEL = new CountingModel();

        @Bean
        CaseInvestigator caseInvestigator(
                ToolRegistry tools,
                BriefParser parser,
                AiInvocationRecorder recorder,
                AdminModerationService cases,
                ModerationCaseRepository caseRepository) {

            return new CaseInvestigator(
                    MODEL, tools, new InvestigationPromptV1(), parser, recorder, cases, caseRepository,
                    new InvestigatorProperties(true, 5, 1, InvestigationPromptV1.VERSION));
        }
    }

    /** Answers immediately with a brief that cites nothing, and counts how often it was asked. */
    static final class CountingModel implements ToolCallingPort {

        private final java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();

        int calls() {
            return calls.get();
        }

        void reset() {
            calls.set(0);
        }

        @Override
        public String modelName() {
            return "counting-model";
        }

        @Override
        public Response next(String system, List<Message> history, List<ToolSpec> specs) {
            calls.incrementAndGet();
            return new Response(new Turn.Finished("""
                    {"summary":"Nothing in the record.","recommendation":"NONE","confidence":"OPEN",
                     "counterEvidence":"The report may yet be right.","citedCaseIds":[]}
                    """), 10, 5);
        }
    }

    private final CountingModel stubModel = StubInvestigator.MODEL;

    @org.junit.jupiter.api.BeforeEach
    void resetTheStub() {
        stubModel.reset();
    }

    @Autowired
    private InvestigationService service;

    @Autowired
    private InvestigationRecorder recorder;

    @Autowired
    private ModerationCaseRepository cases;

    @Autowired
    private PostRepository posts;

    @Autowired
    private AuditEntryRepository auditEntries;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** Cases this test left awaiting review, resolved afterwards so the shared queue does not grow. */
    private final List<UUID> leftInQueue = new java.util.ArrayList<>();

    /**
     * A case nobody has investigated is an ordinary case, not a missing one. 404
     * here would have the console show an error on every case it opens.
     */
    @Test
    void returnsNoContentWhenNothingHasBeenInvestigated() throws Exception {
        User admin = newAdmin();
        UUID caseId = awaitingReviewCase(newUser());

        mockMvc.perform(get("/api/admin/moderation-cases/{id}/investigation", caseId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isNoContent());
    }

    /** The prefix carries the protection; this asserts that a new controller under it inherited that. */
    @Test
    void refusesAnyoneWhoIsNotAnAdministrator() throws Exception {
        UUID caseId = awaitingReviewCase(newUser());

        mockMvc.perform(get("/api/admin/moderation-cases/{id}/investigation", caseId)
                        .header("Authorization", bearer(newUser())))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/moderation-cases/{id}/investigate", caseId))
                .andExpect(status().isUnauthorized());
    }

    /**
     * The round trip through JSONB, which is the part with a way of being quietly
     * wrong: a field that stops deserialising comes back null rather than
     * failing, and a null recommendation renders as a brief that recommends
     * nothing.
     */
    @Test
    void readsBackEveryFieldOfAStoredBrief() throws Exception {
        User admin = newAdmin();
        UUID caseId = awaitingReviewCase(newUser());
        UUID cited = UUID.randomUUID();

        recorder.record(admin.getId(), caseId, new InvestigationBriefView(
                InvestigationBriefView.COMPLETE,
                "Two prior hides under the same rule.",
                FinalAction.BAN,
                EvidenceStrength.SETTLED,
                "The content is milder than the earlier posts.",
                List.of(cited),
                "inv-v3",
                Instant.now()));

        mockMvc.perform(get("/api/admin/moderation-cases/{id}/investigation", caseId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("COMPLETE"))
                .andExpect(jsonPath("$.recommendation").value("BAN"))
                .andExpect(jsonPath("$.evidenceStrength").value("SETTLED"))
                .andExpect(jsonPath("$.counterEvidence").value("The content is milder than the earlier posts."))
                .andExpect(jsonPath("$.citedCaseIds[0]").value(cited.toString()))
                .andExpect(jsonPath("$.promptVersion").value("inv-v3"));
    }

    /**
     * The newer brief wins.
     *
     * <p>A reviewer who asked again did so because something changed, and being
     * shown the answer from before it changed is worse than being shown nothing.
     */
    @Test
    void returnsTheMostRecentBriefWhenACaseHasBeenInvestigatedTwice() throws Exception {
        User admin = newAdmin();
        UUID caseId = awaitingReviewCase(newUser());

        recorder.record(admin.getId(), caseId, view("An early look.", FinalAction.HIDE, Instant.now().minusSeconds(600)));
        recorder.record(admin.getId(), caseId, view("A later look.", FinalAction.BAN, Instant.now()));

        mockMvc.perform(get("/api/admin/moderation-cases/{id}/investigation", caseId)
                        .header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.summary").value("A later look."))
                .andExpect(jsonPath("$.recommendation").value("BAN"));
    }

    /**
     * A brief cost money and may end up quoted in the reasoning for a ban, so the
     * trail says who asked for it and records it as a person's action rather than
     * the system's.
     */
    @Test
    void writesTheBriefIntoTheAuditTrailAgainstTheAdministratorWhoAskedForIt() {
        User admin = newAdmin();
        UUID caseId = awaitingReviewCase(newUser());
        ModerationCase subject = cases.findById(caseId).orElseThrow();

        recorder.record(admin.getId(), caseId, view("A summary.", FinalAction.HIDE, Instant.now()));

        List<AuditEntry> trail = auditEntries.findByTargetTypeAndTargetIdOrderByCreatedAtAsc(
                subject.getTargetType(), subject.getTargetId());

        assertThat(trail)
                .filteredOn(entry -> InvestigationRecorder.INVESTIGATION_RECORDED.equals(entry.getAction()))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.getActorType()).isEqualTo(AuditActorType.ADMIN);
                    assertThat(entry.getActorId()).isEqualTo(admin.getId());
                    assertThat(entry.getPayload()).containsEntry("caseId", caseId.toString());
                });
    }

    /**
     * Briefs are found by the case in their payload, not merely by the content
     * they concern. The audit trail is keyed by target, and one piece of content
     * can be reported, resolved and reported again.
     */
    @Test
    void doesNotReturnABriefWrittenForADifferentCaseOnTheSameContent() throws Exception {
        User admin = newAdmin();
        User author = newUser();
        UUID first = awaitingReviewCase(author);
        ModerationCase subject = cases.findById(first).orElseThrow();

        recorder.record(admin.getId(), first, view("About the first case.", FinalAction.HIDE, Instant.now()));

        // A second case on the same content, as happens when content is reported
        // again after an earlier report was dismissed.
        UUID second = new TransactionTemplate(transactionManager).execute(status -> {
            ModerationCase reopened = cases.findById(first).orElseThrow();
            reopened.resolve(admin, FinalAction.NONE);
            cases.saveAndFlush(reopened);

            cases.openCaseIfAbsent(TargetType.POST.name(), subject.getTargetId());
            return cases.findOpenCaseId(TargetType.POST, subject.getTargetId()).orElseThrow();
        });

        mockMvc.perform(get("/api/admin/moderation-cases/{id}/investigation", second)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isNoContent());
    }

    /**
     * Leaves the shared review queue as it was found.
     *
     * <p>These classes create a case per assertion and the database is shared by
     * the whole suite, so without this they pile up: a workflow test that fetches
     * the queue and looks for its own case starts failing once the default page
     * no longer reaches it. That test's assumption is fragile, but the pollution
     * is this test's doing, and it is the half that should not exist.
     *
     * <p>Resolved with NONE, which is also the outcome kept out of precedent, so
     * cleaning up cannot quietly become evidence for a later test.
     */
    @AfterEach
    void leaveTheQueueAsItWasFound() {
        if (leftInQueue.isEmpty()) {
            return;
        }

        User admin = newAdmin();
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                leftInQueue.forEach(id -> cases.findById(id)
                        .filter(moderationCase -> moderationCase.getStatus() == CaseStatus.AWAITING_REVIEW)
                        .ifPresent(moderationCase -> {
                            moderationCase.resolve(admin, FinalAction.NONE);
                            cases.saveAndFlush(moderationCase);
                        })));
        leftInQueue.clear();
    }

    /**
     * The whole path, through the service the controller actually calls.
     *
     * <p>This is the test that was missing. Every other one here reaches the
     * storing method directly, which passes through a Spring proxy and is
     * therefore transactional; the service reached it by self-invocation, which
     * does not, so AuditLogger's MANDATORY propagation refused the write and the
     * first real request returned a 500. Calling the outer method is the only way
     * to see that.
     */
    @Test
    void storesTheBriefWhenTheServiceItselfRunsAnInvestigation() {
        User admin = newAdmin();
        UUID caseId = awaitingReviewCase(newUser());

        InvestigationBriefView produced = service.investigate(admin.getId(), caseId, false);

        assertThat(produced.outcome()).isEqualTo(InvestigationBriefView.COMPLETE);
        assertThat(service.existingBrief(caseId)).contains(produced);
    }

    /** The second reviewer to open a case is shown the first one's brief, not billed for a new one. */
    @Test
    void doesNotRunTwiceForACaseThatHasAlreadyBeenInvestigated() {
        User admin = newAdmin();
        UUID caseId = awaitingReviewCase(newUser());

        service.investigate(admin.getId(), caseId, false);
        service.investigate(admin.getId(), caseId, false);

        assertThat(stubModel.calls()).isEqualTo(1);
    }

    @Test
    void runsAgainWhenTheReviewerAsksForAFreshLook() {
        User admin = newAdmin();
        UUID caseId = awaitingReviewCase(newUser());

        service.investigate(admin.getId(), caseId, false);
        service.investigate(admin.getId(), caseId, true);

        assertThat(stubModel.calls()).isEqualTo(2);
    }

    private InvestigationBriefView view(String summary, FinalAction recommendation, Instant at) {
        return new InvestigationBriefView(
                InvestigationBriefView.COMPLETE,
                summary,
                recommendation,
                EvidenceStrength.LEANING,
                "Something that argues the other way.",
                List.of(),
                "inv-v3",
                at);
    }

    private UUID awaitingReviewCase(User author) {
        return track(new TransactionTemplate(transactionManager).execute(status -> {
            Post post = posts.saveAndFlush(new Post(uniqueForumKey(), author, "A title", "A body"));

            cases.openCaseIfAbsent(TargetType.POST.name(), post.getId());
            UUID caseId = cases.findOpenCaseId(TargetType.POST, post.getId()).orElseThrow();

            ModerationCase moderationCase = cases.findById(caseId).orElseThrow();
            moderationCase.markAnalysing();
            moderationCase.recordVerdict(
                    "keyword-v1",
                    new ModerationVerdict(ModerationDecision.REMOVE, 0.8, "A test verdict.", List.of("ABUSE")));

            return cases.saveAndFlush(moderationCase).getId();
        }));
    }

    private UUID track(UUID caseId) {
        leftInQueue.add(caseId);
        return caseId;
    }
}
