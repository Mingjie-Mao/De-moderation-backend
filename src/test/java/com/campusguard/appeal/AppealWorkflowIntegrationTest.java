package com.campusguard.appeal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campusguard.AbstractIntegrationTest;
import com.campusguard.common.TargetType;
import com.campusguard.moderation.FinalAction;
import com.campusguard.moderation.ModerationCaseRepository;
import com.campusguard.moderation.ModerationWorker;
import com.campusguard.moderation.admin.CaseDecisionRequest;
import com.campusguard.post.CreatePostRequest;
import com.campusguard.report.CreateReportRequest;
import com.campusguard.report.ReportReason;
import com.campusguard.user.User;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class AppealWorkflowIntegrationTest extends AbstractIntegrationTest {

    @Autowired ModerationCaseRepository cases;
    @Autowired ModerationWorker worker;

    @Test
    void affectedAuthorCanAppealAndAnOverturnRestoresContent() throws Exception {
        User author = newUser();
        User reporter = newUser();
        User admin = newAdmin();
        UUID postId = createPost(author);
        UUID caseId = reportAndAnalyse(reporter, postId);

        decide(admin, caseId, FinalAction.HIDE).andExpect(status().isOk());
        mockMvc.perform(get("/api/posts/{id}", postId)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/notifications").header("Authorization", bearer(author)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("MODERATION_DECISION_APPEALABLE"));
        mockMvc.perform(get("/api/notifications").header("Authorization", bearer(reporter)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("MODERATION_DECISION"));

        String created = mockMvc.perform(post("/api/appeals")
                        .header("Authorization", bearer(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateAppealRequest(caseId, "The quoted text was taken out of context."))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        UUID appealId = UUID.fromString(JsonPath.read(created, "$.id"));

        mockMvc.perform(post("/api/appeals")
                        .header("Authorization", bearer(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateAppealRequest(caseId, "Duplicate"))))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/admin/appeals/{id}/decision", appealId)
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new AppealDecisionRequest(
                                AppealDecision.OVERTURN, "Context confirms this should remain visible."))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OVERTURNED"));

        mockMvc.perform(get("/api/posts/{id}", postId)).andExpect(status().isOk());
        mockMvc.perform(get("/api/notifications").header("Authorization", bearer(author)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type=='APPEAL_DECISION')]").exists());
    }

    @Test
    void unrelatedMemberCannotAppealSomebodyElsesDecision() throws Exception {
        User author = newUser();
        User admin = newAdmin();
        UUID postId = createPost(author);
        UUID caseId = reportAndAnalyse(newUser(), postId);
        decide(admin, caseId, FinalAction.HIDE).andExpect(status().isOk());

        mockMvc.perform(post("/api/appeals")
                        .header("Authorization", bearer(newUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateAppealRequest(caseId, "I do not own this."))))
                .andExpect(status().isForbidden());
    }

    private UUID createPost(User author) throws Exception {
        String body = mockMvc.perform(post("/api/posts")
                        .header("Authorization", bearer(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreatePostRequest(uniqueForumKey(), "Appealable", "You are an idiot"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private UUID reportAndAnalyse(User reporter, UUID postId) throws Exception {
        mockMvc.perform(post("/api/reports")
                        .header("Authorization", bearer(reporter))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateReportRequest(TargetType.POST, postId, ReportReason.ABUSE))))
                .andExpect(status().isCreated());
        UUID caseId = cases.findOpenByTarget(TargetType.POST, postId).orElseThrow().getId();
        for (int i = 0; i < 20 && cases.findById(caseId).orElseThrow().getStatus().name().equals("QUEUED"); i++)
            worker.runOnce();
        return caseId;
    }

    private org.springframework.test.web.servlet.ResultActions decide(
            User admin, UUID caseId, FinalAction action) throws Exception {
        return mockMvc.perform(post("/api/admin/moderation-cases/{id}/decision", caseId)
                .header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(new CaseDecisionRequest(action, "Test decision"))));
    }
}
