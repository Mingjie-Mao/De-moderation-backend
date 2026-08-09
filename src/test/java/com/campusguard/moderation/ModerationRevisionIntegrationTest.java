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
import com.campusguard.user.User;
import com.campusguard.user.UserRepository;
import com.campusguard.user.UserStatus;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Correcting a decision that has already been made.
 *
 * <p>A reviewer acting on an engine's recommendation will sometimes be wrong,
 * and a workflow whose outcomes are final makes every such mistake permanent.
 * These tests pin the part that is easy to get wrong: the effects of the old
 * decision have to be undone, but only the effects the new decision does not
 * also want.
 */
class ModerationRevisionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ModerationCaseRepository caseRepository;

    @Autowired
    private ModerationWorker worker;

    @Autowired
    private AuditEntryRepository auditEntryRepository;

    @Autowired
    private UserRepository users;

    @Test
    void undoingAHideMakesTheContentReadableAgain() throws Exception {
        User author = newUser();
        User admin = newAdmin();
        String forumKey = uniqueForumKey();
        UUID postId = createPost(author, forumKey, "You are an idiot and always have been");

        UUID caseId = reportedCaseFor(postId);
        decide(admin, caseId, FinalAction.HIDE).andExpect(status().isOk());
        mockMvc.perform(get("/api/posts/{id}", postId)).andExpect(status().isNotFound());

        decide(admin, caseId, FinalAction.NONE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moderationCase.status").value("RESOLVED"))
                .andExpect(jsonPath("$.moderationCase.finalAction").value("NONE"));

        mockMvc.perform(get("/api/posts/{id}", postId)).andExpect(status().isOk());
        mockMvc.perform(get("/api/posts").param("forum", forumKey))
                .andExpect(jsonPath("$.items.length()").value(1));

        assertThat(actionsFor(postId))
                .containsSubsequence(
                        AuditLogger.CONTENT_HIDDEN,
                        AuditLogger.CASE_RESOLVED,
                        AuditLogger.CONTENT_RESTORED,
                        AuditLogger.CASE_DECISION_REVISED);
    }

    @Test
    void undoingABanRestoresTheContentAndTheAccount() throws Exception {
        User author = newUser();
        User admin = newAdmin();
        UUID postId = createPost(author, uniqueForumKey(), "代写论文 包过 私聊");

        UUID caseId = reportedCaseFor(postId);
        decide(admin, caseId, FinalAction.BAN).andExpect(status().isOk());
        assertThat(users.findById(author.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.BANNED);

        decide(admin, caseId, FinalAction.NONE).andExpect(status().isOk());

        assertThat(users.findById(author.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
        mockMvc.perform(get("/api/posts/{id}", postId)).andExpect(status().isOk());
        assertThat(actionsFor(postId)).contains(AuditLogger.AUTHOR_REINSTATED);
    }

    /**
     * The case a naive "undo everything, then redo" would get wrong: both
     * outcomes take the content down, so it must never come back in between.
     */
    @Test
    void softeningABanToAHideKeepsTheContentDown() throws Exception {
        User author = newUser();
        User admin = newAdmin();
        UUID postId = createPost(author, uniqueForumKey(), "代写论文 包过 私聊");

        UUID caseId = reportedCaseFor(postId);
        decide(admin, caseId, FinalAction.BAN).andExpect(status().isOk());
        decide(admin, caseId, FinalAction.HIDE).andExpect(status().isOk());

        assertThat(users.findById(author.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
        mockMvc.perform(get("/api/posts/{id}", postId)).andExpect(status().isNotFound());
        assertThat(actionsFor(postId)).doesNotContain(AuditLogger.CONTENT_RESTORED);
    }

    @Test
    void escalatingAHideToABanBansWithoutEverRestoring() throws Exception {
        User author = newUser();
        User admin = newAdmin();
        UUID postId = createPost(author, uniqueForumKey(), "You are an idiot");

        UUID caseId = reportedCaseFor(postId);
        decide(admin, caseId, FinalAction.HIDE).andExpect(status().isOk());
        decide(admin, caseId, FinalAction.BAN).andExpect(status().isOk());

        assertThat(users.findById(author.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.BANNED);
        mockMvc.perform(get("/api/posts/{id}", postId)).andExpect(status().isNotFound());
        assertThat(actionsFor(postId)).doesNotContain(AuditLogger.CONTENT_RESTORED);
    }

    /** The correction names whoever made it, so the record always answers "who decided what stands". */
    @Test
    void theRevisingAdministratorBecomesTheRecordedDecider() throws Exception {
        User first = newAdmin();
        User second = newAdmin();
        UUID postId = createPost(newUser(), uniqueForumKey(), "You are an idiot");

        UUID caseId = reportedCaseFor(postId);
        decide(first, caseId, FinalAction.HIDE).andExpect(status().isOk());

        decide(second, caseId, FinalAction.NONE)
                .andExpect(jsonPath("$.moderationCase.decidedBy").value(second.getId().toString()));
    }

    @Test
    void recordsWhatTheDecisionWasChangedFrom() throws Exception {
        User admin = newAdmin();
        UUID postId = createPost(newUser(), uniqueForumKey(), "You are an idiot");

        UUID caseId = reportedCaseFor(postId);
        decide(admin, caseId, FinalAction.HIDE).andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/moderation-cases/{id}/decision", caseId)
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CaseDecisionRequest(FinalAction.NONE, "Read it again in context."))))
                .andExpect(status().isOk());

        AuditEntry revision = auditEntryRepository
                .findByTargetTypeAndTargetIdOrderByCreatedAtAsc(TargetType.POST, postId)
                .stream()
                .filter(entry -> entry.getAction().equals(AuditLogger.CASE_DECISION_REVISED))
                .findFirst()
                .orElseThrow();

        assertThat(revision.getPayload()).containsEntry("previousAction", "HIDE");
        assertThat(revision.getPayload()).containsEntry("action", "NONE");
        assertThat(revision.getPayload()).containsEntry("note", "Read it again in context.");
    }

    @Test
    void refusesARevisionThatChangesNothing() throws Exception {
        User admin = newAdmin();
        UUID postId = createPost(newUser(), uniqueForumKey(), "You are an idiot");

        UUID caseId = reportedCaseFor(postId);
        decide(admin, caseId, FinalAction.HIDE).andExpect(status().isOk());
        decide(admin, caseId, FinalAction.HIDE).andExpect(status().isConflict());
    }

    /** Revising is still an administrator's act, not something a member can reach. */
    @Test
    void keepsMembersOutOfRevisions() throws Exception {
        User admin = newAdmin();
        UUID postId = createPost(newUser(), uniqueForumKey(), "You are an idiot");

        UUID caseId = reportedCaseFor(postId);
        decide(admin, caseId, FinalAction.HIDE).andExpect(status().isOk());

        decide(newUser(), caseId, FinalAction.NONE).andExpect(status().isForbidden());
    }

    /**
     * An account can be banned by more than one case. Undoing one of them says
     * that decision was wrong; it says nothing about the other, so the ban
     * stands and the account is not quietly let back in.
     */
    @Test
    void undoingOneBanLeavesAnAccountBannedByAnotherCase() throws Exception {
        User author = newUser();
        User admin = newAdmin();

        UUID firstPost = createPost(author, uniqueForumKey(), "代写论文 包过 私聊");
        UUID secondPost = createPost(author, uniqueForumKey(), "You are an idiot and always have been");

        UUID firstCase = reportedCaseFor(firstPost);
        UUID secondCase = reportedCaseFor(secondPost);

        decide(admin, firstCase, FinalAction.BAN).andExpect(status().isOk());
        decide(admin, secondCase, FinalAction.BAN).andExpect(status().isOk());
        assertThat(users.findById(author.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.BANNED);

        decide(admin, secondCase, FinalAction.NONE).andExpect(status().isOk());

        assertThat(users.findById(author.getId()).orElseThrow().getStatus())
                .as("the first case still bans this author")
                .isEqualTo(UserStatus.BANNED);
        assertThat(actionsFor(secondPost))
                .contains(AuditLogger.BAN_UPHELD_ELSEWHERE)
                .doesNotContain(AuditLogger.AUTHOR_REINSTATED);

        // The content of the case actually being corrected still comes back.
        mockMvc.perform(get("/api/posts/{id}", secondPost)).andExpect(status().isOk());
    }

    /** With the last standing ban undone, the account is reinstated as normal. */
    @Test
    void undoingTheLastBanReinstatesTheAccount() throws Exception {
        User author = newUser();
        User admin = newAdmin();

        UUID firstPost = createPost(author, uniqueForumKey(), "代写论文 包过 私聊");
        UUID secondPost = createPost(author, uniqueForumKey(), "You are an idiot and always have been");

        UUID firstCase = reportedCaseFor(firstPost);
        UUID secondCase = reportedCaseFor(secondPost);

        decide(admin, firstCase, FinalAction.BAN).andExpect(status().isOk());
        decide(admin, secondCase, FinalAction.BAN).andExpect(status().isOk());

        decide(admin, secondCase, FinalAction.NONE).andExpect(status().isOk());
        decide(admin, firstCase, FinalAction.NONE).andExpect(status().isOk());

        assertThat(users.findById(author.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(actionsFor(firstPost)).contains(AuditLogger.AUTHOR_REINSTATED);
    }

    /**
     * Reconsidering a hide means reading what was hidden. The console keeps
     * showing removed content for exactly that reason, even though every public
     * route stops serving it.
     */
    @Test
    void theConsoleStillShowsContentItHasHidden() throws Exception {
        User admin = newAdmin();
        UUID postId = createPost(newUser(), uniqueForumKey(), "You are an idiot and always have been");

        UUID caseId = reportedCaseFor(postId);
        decide(admin, caseId, FinalAction.HIDE).andExpect(status().isOk());

        mockMvc.perform(get("/api/posts/{id}", postId)).andExpect(status().isNotFound());

        mockMvc.perform(get("/api/admin/moderation-cases/{id}", caseId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.body").value("You are an idiot and always have been"));
    }

    private List<String> actionsFor(UUID postId) {
        return auditEntryRepository
                .findByTargetTypeAndTargetIdOrderByCreatedAtAsc(TargetType.POST, postId)
                .stream()
                .map(AuditEntry::getAction)
                .toList();
    }

    private UUID reportedCaseFor(UUID postId) throws Exception {
        mockMvc.perform(post("/api/reports")
                        .header("Authorization", bearer(newUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateReportRequest(TargetType.POST, postId, ReportReason.ABUSE))))
                .andExpect(status().isCreated());

        for (int attempt = 0; attempt < 20; attempt++) {
            ModerationCase current = openCaseFor(postId);
            if (current.getStatus() != CaseStatus.QUEUED) {
                return current.getId();
            }
            worker.runOnce();
        }
        throw new AssertionError("Case for " + postId + " never left the queue.");
    }

    private ModerationCase openCaseFor(UUID postId) {
        return caseRepository
                .findOpenByTarget(TargetType.POST, postId)
                .orElseThrow(() -> new AssertionError("No open case for post " + postId));
    }

    private ResultActions decide(User admin, UUID caseId, FinalAction action) throws Exception {
        return mockMvc.perform(post("/api/admin/moderation-cases/{id}/decision", caseId)
                .header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(new CaseDecisionRequest(action, null))));
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
