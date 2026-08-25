package com.campusguard.moderation.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campusguard.AbstractIntegrationTest;
import com.campusguard.common.TargetType;
import com.campusguard.moderation.FinalAction;
import com.campusguard.moderation.ModerationCaseRepository;
import com.campusguard.moderation.ModerationWorker;
import com.campusguard.post.CreatePostRequest;
import com.campusguard.report.CreateReportRequest;
import com.campusguard.report.ReportReason;
import com.campusguard.user.User;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class CaseAssignmentIntegrationTest extends AbstractIntegrationTest {
    @Autowired ModerationCaseRepository cases;
    @Autowired ModerationWorker worker;

    @Test
    void assignmentPreventsASecondReviewerFromDecidingTheCase() throws Exception {
        User first = newAdmin();
        User second = newAdmin();
        UUID caseId = awaitingCase();

        mockMvc.perform(post("/api/admin/moderation-cases/{id}/assignment", caseId)
                        .header("Authorization", bearer(first)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moderationCase.assignedTo").value(first.getId().toString()));

        mockMvc.perform(post("/api/admin/moderation-cases/{id}/decision", caseId)
                        .header("Authorization", bearer(second))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CaseDecisionRequest(FinalAction.NONE, "Racing reviewer"))))
                .andExpect(status().isConflict());

        mockMvc.perform(delete("/api/admin/moderation-cases/{id}/assignment", caseId)
                        .header("Authorization", bearer(first)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moderationCase.assignedTo").doesNotExist());
    }

    private UUID awaitingCase() throws Exception {
        User author = newUser();
        String postBody = mockMvc.perform(post("/api/posts")
                        .header("Authorization", bearer(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreatePostRequest(uniqueForumKey(), "Assign", "Body"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID postId = UUID.fromString(JsonPath.read(postBody, "$.id"));
        mockMvc.perform(post("/api/reports")
                        .header("Authorization", bearer(newUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateReportRequest(TargetType.POST, postId, ReportReason.OTHER))))
                .andExpect(status().isCreated());
        UUID caseId = cases.findOpenByTarget(TargetType.POST, postId).orElseThrow().getId();
        for (int i = 0; i < 20 && cases.findById(caseId).orElseThrow().getStatus().name().equals("QUEUED"); i++)
            worker.runOnce();
        return caseId;
    }
}
