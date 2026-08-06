package com.campusguard.report;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campusguard.AbstractIntegrationTest;
import com.campusguard.common.TargetType;
import com.campusguard.post.CreatePostRequest;
import com.campusguard.user.User;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class ReportApiIntegrationTest extends AbstractIntegrationTest {

    @Test
    void reportsAPostAndStartsItInPendingState() throws Exception {
        User author = newUser();
        User reporter = newUser();
        UUID postId = createPost(author);

        String body = mockMvc.perform(post("/api/reports")
                        .header("X-User-Id", reporter.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateReportRequest(TargetType.POST, postId, ReportReason.SPAM))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.targetType").value("POST"))
                .andExpect(jsonPath("$.targetId").value(postId.toString()))
                .andExpect(jsonPath("$.reporter.username").value(reporter.getUsername()))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        UUID reportId = UUID.fromString(JsonPath.read(body, "$.id"));

        mockMvc.perform(get("/api/reports/{id}", reportId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("SPAM"));
    }

    /**
     * Repeat reports from one account add no signal and would distort the count
     * that drives moderation priority, so the second one is refused.
     */
    @Test
    void refusesASecondReportOfTheSameTargetByTheSameUser() throws Exception {
        User author = newUser();
        User reporter = newUser();
        UUID postId = createPost(author);

        submitReport(reporter, postId, ReportReason.SPAM).andExpect(status().isCreated());

        mockMvc.perform(post("/api/reports")
                        .header("X-User-Id", reporter.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateReportRequest(TargetType.POST, postId, ReportReason.ABUSE))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflicting request"));
    }

    /**
     * Two accounts reporting the same content is the normal path: it is exactly
     * what the aggregation into a single moderation case is built to collapse.
     */
    @Test
    void allowsDifferentUsersToReportTheSameTarget() throws Exception {
        User author = newUser();
        UUID postId = createPost(author);

        submitReport(newUser(), postId, ReportReason.SPAM).andExpect(status().isCreated());
        submitReport(newUser(), postId, ReportReason.ABUSE).andExpect(status().isCreated());
    }

    /**
     * Reports carry no foreign key to their target, so nothing but this check
     * stands between the table and rows pointing at content that never existed.
     */
    @Test
    void rejectsAReportAgainstContentThatDoesNotExist() throws Exception {
        mockMvc.perform(post("/api/reports")
                        .header("X-User-Id", newUser().getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateReportRequest(
                                TargetType.POST, UUID.randomUUID(), ReportReason.SPAM))))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsAReportWithNoReason() throws Exception {
        User author = newUser();
        UUID postId = createPost(author);

        mockMvc.perform(post("/api/reports")
                        .header("X-User-Id", newUser().getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateReportRequest(TargetType.POST, postId, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.reason").isNotEmpty());
    }

    private org.springframework.test.web.servlet.ResultActions submitReport(
            User reporter, UUID postId, ReportReason reason) throws Exception {
        return mockMvc.perform(post("/api/reports")
                .header("X-User-Id", reporter.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(new CreateReportRequest(TargetType.POST, postId, reason))));
    }

    private UUID createPost(User author) throws Exception {
        String body = mockMvc.perform(post("/api/posts")
                        .header("X-User-Id", author.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreatePostRequest(uniqueForumKey(), "Title", "Body"))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return UUID.fromString(JsonPath.read(body, "$.id"));
    }
}
