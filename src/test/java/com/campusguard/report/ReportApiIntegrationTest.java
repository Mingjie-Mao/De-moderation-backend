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
    void reportsAPostAndAggregatesItIntoACaseImmediately() throws Exception {
        User author = newUser();
        User reporter = newUser();
        UUID postId = createPost(author);

        String body = mockMvc.perform(post("/api/reports")
                        .header("Authorization", bearer(reporter))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateReportRequest(TargetType.POST, postId, ReportReason.SPAM))))
                .andExpect(status().isCreated())
                // Filing a report opens or joins its case in the same
                // transaction, so it is aggregated by the time anyone can see it.
                .andExpect(jsonPath("$.status").value("AGGREGATED"))
                .andExpect(jsonPath("$.targetType").value("POST"))
                .andExpect(jsonPath("$.targetId").value(postId.toString()))
                .andExpect(jsonPath("$.reporter.username").value(reporter.getUsername()))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        UUID reportId = UUID.fromString(JsonPath.read(body, "$.id"));

        mockMvc.perform(get("/api/reports/{id}", reportId).header("Authorization", bearer(reporter)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("SPAM"));
    }

    /**
     * A report names the person who filed it. Letting any authenticated account
     * read any report by id would hand the reported user the identity of whoever
     * turned them in, which is the one thing a reporting feature must not do.
     */
    @Test
    void aStrangerCannotReadSomeoneElsesReport() throws Exception {
        User author = newUser();
        User reporter = newUser();
        UUID postId = createPost(author);

        UUID reportId = UUID.fromString(JsonPath.read(
                submitReport(reporter, postId, ReportReason.SPAM)
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id"));

        mockMvc.perform(get("/api/reports/{id}", reportId).header("Authorization", bearer(author)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Access denied"));
    }

    @Test
    void anAdministratorCanReadAnyReport() throws Exception {
        User author = newUser();
        User reporter = newUser();
        User admin = newAdmin();
        UUID postId = createPost(author);

        UUID reportId = UUID.fromString(JsonPath.read(
                submitReport(reporter, postId, ReportReason.ABUSE)
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id"));

        mockMvc.perform(get("/api/reports/{id}", reportId).header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reporter.username").value(reporter.getUsername()));
    }

    @Test
    void readingAReportWithoutATokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/reports/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
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
                        .header("Authorization", bearer(reporter))
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
                        .header("Authorization", bearer(newUser()))
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
                        .header("Authorization", bearer(newUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateReportRequest(TargetType.POST, postId, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.reason").isNotEmpty());
    }

    private org.springframework.test.web.servlet.ResultActions submitReport(
            User reporter, UUID postId, ReportReason reason) throws Exception {
        return mockMvc.perform(post("/api/reports")
                .header("Authorization", bearer(reporter))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(new CreateReportRequest(TargetType.POST, postId, reason))));
    }

    private UUID createPost(User author) throws Exception {
        String body = mockMvc.perform(post("/api/posts")
                        .header("Authorization", bearer(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreatePostRequest(uniqueForumKey(), "Title", "Body"))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return UUID.fromString(JsonPath.read(body, "$.id"));
    }
}
