package com.campusguard.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campusguard.AbstractIntegrationTest;
import com.campusguard.audit.AuditEntry;
import com.campusguard.audit.AuditEntryRepository;
import com.campusguard.audit.AuditLogger;
import com.campusguard.common.TargetType;
import com.campusguard.moderation.admin.CaseDecisionRequest;
import com.campusguard.post.CreatePostRequest;
import com.campusguard.report.CreateReportRequest;
import com.campusguard.report.ReportReason;
import com.campusguard.report.ReportRepository;
import com.campusguard.report.ReportStatus;
import com.campusguard.user.User;
import com.campusguard.user.UserRepository;
import com.campusguard.user.UserStatus;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * The whole path a report takes: filed by a member, aggregated into a case,
 * judged by an engine, and finally acted on by a person.
 */
class ModerationWorkflowIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ModerationCaseRepository caseRepository;

    @Autowired
    private ModerationWorker worker;

    @Autowired
    private AuditEntryRepository auditEntryRepository;

    @Autowired
    private UserRepository users;

    @Autowired
    private ReportRepository reports;

    @Test
    void carriesAReportFromFilingThroughToAnAdministratorsDecision() throws Exception {
        User author = newUser();
        User reporter = newUser();
        User admin = newAdmin();

        String forumKey = uniqueForumKey();
        UUID postId = createPost(author, forumKey, "You are an idiot and always have been");

        report(reporter, postId, ReportReason.ABUSE).andExpect(status().isCreated());

        ModerationCase queued = openCaseFor(postId);
        assertThat(queued.getStatus()).isEqualTo(CaseStatus.QUEUED);
        assertThat(queued.getReportCount()).isEqualTo(1);
        assertThat(queued.getDecision()).isNull();

        drainQueue(postId);

        ModerationCase judged = openCaseFor(postId);
        assertThat(judged.getStatus()).isEqualTo(CaseStatus.AWAITING_REVIEW);
        assertThat(judged.getEngine()).isEqualTo("keyword-v1");
        assertThat(judged.getDecision()).isEqualTo(ModerationDecision.REMOVE);
        assertThat(judged.getRuleCodes()).contains("ABUSE");
        assertThat(judged.getRationale()).isNotBlank();

        // The engine advised removal and the content is still there. Nothing is
        // taken down until a person says so, which is the constraint the whole
        // workflow exists to enforce.
        mockMvc.perform(get("/api/posts/{id}", postId)).andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/moderation-cases")
                        .header("Authorization", bearer(admin))
                        .param("status", "AWAITING_REVIEW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + judged.getId() + "')]").exists());

        mockMvc.perform(post("/api/admin/moderation-cases/{id}/decision", judged.getId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CaseDecisionRequest(FinalAction.HIDE, "Clear personal attack."))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moderationCase.status").value("RESOLVED"))
                .andExpect(jsonPath("$.moderationCase.finalAction").value("HIDE"));

        // Now it is gone, from the single fetch and from the feed.
        mockMvc.perform(get("/api/posts/{id}", postId)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/posts").param("forum", forumKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));

        List<String> actions = auditEntryRepository
                .findByTargetTypeAndTargetIdOrderByCreatedAtAsc(TargetType.POST, postId)
                .stream()
                .map(AuditEntry::getAction)
                .toList();

        assertThat(actions)
                .containsSubsequence(
                        AuditLogger.CASE_OPENED,
                        AuditLogger.REPORT_FILED,
                        AuditLogger.CASE_CLAIMED,
                        AuditLogger.VERDICT_RECORDED,
                        AuditLogger.CONTENT_HIDDEN,
                        AuditLogger.CASE_RESOLVED);
    }

    /**
     * The engine sees nothing wrong, a person disagrees, and the disagreement is
     * recorded. That flag is the only way to find out later whether the engine is
     * worth its cost.
     */
    @Test
    void recordsWhenAnAdministratorOverridesTheEngine() throws Exception {
        User author = newUser();
        User admin = newAdmin();

        UUID postId = createPost(author, uniqueForumKey(), "Nothing here trips a configured term at all");
        report(newUser(), postId, ReportReason.OTHER).andExpect(status().isCreated());
        drainQueue(postId);

        ModerationCase judged = openCaseFor(postId);
        assertThat(judged.getDecision()).isEqualTo(ModerationDecision.ALLOW);

        mockMvc.perform(post("/api/admin/moderation-cases/{id}/decision", judged.getId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CaseDecisionRequest(FinalAction.HIDE, "Engine missed the context."))))
                .andExpect(status().isOk());

        AuditEntry resolution = auditEntryRepository
                .findByTargetTypeAndTargetIdOrderByCreatedAtAsc(TargetType.POST, postId)
                .stream()
                .filter(entry -> entry.getAction().equals(AuditLogger.CASE_RESOLVED))
                .findFirst()
                .orElseThrow();

        assertThat(resolution.getPayload()).containsEntry("overrodeRecommendation", true);
        assertThat(resolution.getPayload()).containsEntry("note", "Engine missed the context.");
    }

    @Test
    void banningAnAuthorHidesTheContentAndSuspendsTheAccount() throws Exception {
        User author = newUser();
        User admin = newAdmin();

        UUID postId = createPost(author, uniqueForumKey(), "代写论文 包过 私聊");
        report(newUser(), postId, ReportReason.ILLEGAL).andExpect(status().isCreated());
        drainQueue(postId);

        ModerationCase judged = openCaseFor(postId);

        mockMvc.perform(post("/api/admin/moderation-cases/{id}/decision", judged.getId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CaseDecisionRequest(FinalAction.BAN, "Selling academic fraud."))))
                .andExpect(status().isOk());

        assertThat(users.findById(author.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.BANNED);
        mockMvc.perform(get("/api/posts/{id}", postId)).andExpect(status().isNotFound());
    }

    /**
     * Closing a case finishes the reports that caused it. Without this a
     * reporter's submission would sit aggregated forever, which is indistinguishable
     * from having been ignored.
     */
    @Test
    void resolvingACaseAlsoResolvesTheReportsBehindIt() throws Exception {
        User admin = newAdmin();
        UUID postId = createPost(newUser(), uniqueForumKey(), "You are an idiot");

        report(newUser(), postId, ReportReason.ABUSE).andExpect(status().isCreated());
        report(newUser(), postId, ReportReason.ABUSE).andExpect(status().isCreated());
        drainQueue(postId);

        UUID caseId = openCaseFor(postId).getId();
        assertThat(reports.findByCaseId(caseId))
                .allMatch(report -> report.getStatus() == ReportStatus.AGGREGATED);

        decide(admin, caseId, FinalAction.HIDE).andExpect(status().isOk());

        assertThat(reports.findByCaseId(caseId))
                .hasSize(2)
                .allMatch(report -> report.getStatus() == ReportStatus.RESOLVED);
    }

    @Test
    void refusesToResolveTheSameCaseTwice() throws Exception {
        User admin = newAdmin();
        UUID postId = createPost(newUser(), uniqueForumKey(), "You are an idiot");
        report(newUser(), postId, ReportReason.ABUSE).andExpect(status().isCreated());
        drainQueue(postId);

        UUID caseId = openCaseFor(postId).getId();

        decide(admin, caseId, FinalAction.NONE).andExpect(status().isOk());
        decide(admin, caseId, FinalAction.HIDE).andExpect(status().isConflict());
    }

    /** Only administrators reach the console, and a member asking is refused rather than served. */
    @Test
    void keepsOrdinaryMembersOutOfTheConsole() throws Exception {
        User member = newUser();

        mockMvc.perform(get("/api/admin/moderation-cases").header("Authorization", bearer(member)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/moderation-cases"))
                .andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.ResultActions decide(
            User admin, UUID caseId, FinalAction action) throws Exception {
        return mockMvc.perform(post("/api/admin/moderation-cases/{id}/decision", caseId)
                .header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(new CaseDecisionRequest(action, null))));
    }

    /**
     * Drives the worker by hand until this case has moved on.
     *
     * <p>Looped rather than called once because a batch is capped, and cases left
     * queued by other tests in the shared database can fill it.
     */
    private void drainQueue(UUID targetId) {
        for (int attempt = 0; attempt < 20; attempt++) {
            if (openCaseFor(targetId).getStatus() != CaseStatus.QUEUED) {
                return;
            }
            worker.runOnce();
        }
        throw new AssertionError("Case for " + targetId + " never left the queue.");
    }

    private ModerationCase openCaseFor(UUID targetId) {
        return caseRepository
                .findOpenByTarget(TargetType.POST, targetId)
                .orElseThrow(() -> new AssertionError("No open case for post " + targetId));
    }

    private org.springframework.test.web.servlet.ResultActions report(
            User reporter, UUID postId, ReportReason reason) throws Exception {
        return mockMvc.perform(post("/api/reports")
                .header("Authorization", bearer(reporter))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(new CreateReportRequest(TargetType.POST, postId, reason))));
    }

    private UUID createPost(User author, String forumKey, String body) throws Exception {
        String response = mockMvc.perform(post("/api/posts")
                        .header("Authorization", bearer(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreatePostRequest(forumKey, "Test post", body))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return UUID.fromString(JsonPath.read(response, "$.id"));
    }
}
