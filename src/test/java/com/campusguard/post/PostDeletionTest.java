package com.campusguard.post;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campusguard.AbstractIntegrationTest;
import com.campusguard.comment.CreateCommentRequest;
import com.campusguard.common.TargetType;
import com.campusguard.report.CreateReportRequest;
import com.campusguard.report.ReportReason;
import com.campusguard.user.User;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Deletion is where ownership, role and soft-delete filtering all meet. Until
 * this endpoint existed, every {@code deleted_at is null} predicate in the
 * repositories was written but never exercised.
 */
class PostDeletionTest extends AbstractIntegrationTest {

    @Test
    void anAuthorCanDeleteTheirOwnPost() throws Exception {
        User author = newUser();
        String forumKey = uniqueForumKey();
        UUID postId = createPost(author, forumKey);

        mockMvc.perform(delete("/api/posts/{id}", postId).header("Authorization", bearer(author)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/posts/{id}", postId)).andExpect(status().isNotFound());
    }

    @Test
    void aDeletedPostLeavesTheFeed() throws Exception {
        User author = newUser();
        String forumKey = uniqueForumKey();
        UUID kept = createPost(author, forumKey);
        UUID removed = createPost(author, forumKey);

        mockMvc.perform(get("/api/posts").param("forum", forumKey))
                .andExpect(jsonPath("$", hasSize(2)));

        mockMvc.perform(delete("/api/posts/{id}", removed).header("Authorization", bearer(author)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/posts").param("forum", forumKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(kept.toString()));
    }

    /**
     * The heart of the acceptance criterion: a valid token for the wrong person
     * is a 403, not a 404 and not a silent success.
     */
    @Test
    void anotherMemberCannotDeleteSomeoneElsesPost() throws Exception {
        User author = newUser();
        User stranger = newUser();
        UUID postId = createPost(author, uniqueForumKey());

        mockMvc.perform(delete("/api/posts/{id}", postId).header("Authorization", bearer(stranger)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Access denied"));

        mockMvc.perform(get("/api/posts/{id}", postId)).andExpect(status().isOk());
    }

    @Test
    void anAdministratorCanDeleteAnyPost() throws Exception {
        User author = newUser();
        User admin = newAdmin();
        UUID postId = createPost(author, uniqueForumKey());

        mockMvc.perform(delete("/api/posts/{id}", postId).header("Authorization", bearer(admin)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/posts/{id}", postId)).andExpect(status().isNotFound());
    }

    @Test
    void deletingWithoutATokenIsRejectedBeforeOwnershipIsEvenConsidered() throws Exception {
        User author = newUser();
        UUID postId = createPost(author, uniqueForumKey());

        mockMvc.perform(delete("/api/posts/{id}", postId)).andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/posts/{id}", postId)).andExpect(status().isOk());
    }

    @Test
    void deletingAnAlreadyDeletedPostIsNotFound() throws Exception {
        User author = newUser();
        UUID postId = createPost(author, uniqueForumKey());

        mockMvc.perform(delete("/api/posts/{id}", postId).header("Authorization", bearer(author)))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/posts/{id}", postId).header("Authorization", bearer(author)))
                .andExpect(status().isNotFound());
    }

    @Test
    void commentsOnADeletedPostBecomeUnreachable() throws Exception {
        User author = newUser();
        UUID postId = createPost(author, uniqueForumKey());

        mockMvc.perform(post("/api/posts/{postId}/comments", postId)
                        .header("Authorization", bearer(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateCommentRequest(null, "Still here?"))))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/api/posts/{id}", postId).header("Authorization", bearer(author)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/posts/{postId}/comments", postId)).andExpect(status().isNotFound());

        mockMvc.perform(post("/api/posts/{postId}/comments", postId)
                        .header("Authorization", bearer(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateCommentRequest(null, "Too late"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void aDeletedPostCanNoLongerBeReported() throws Exception {
        User author = newUser();
        User reporter = newUser();
        UUID postId = createPost(author, uniqueForumKey());

        mockMvc.perform(delete("/api/posts/{id}", postId).header("Authorization", bearer(author)))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/reports")
                        .header("Authorization", bearer(reporter))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateReportRequest(TargetType.POST, postId, ReportReason.SPAM))))
                .andExpect(status().isNotFound());
    }

    private UUID createPost(User author, String forumKey) throws Exception {
        String body = mockMvc.perform(post("/api/posts")
                        .header("Authorization", bearer(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreatePostRequest(forumKey, "Title", "Body"))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return UUID.fromString(JsonPath.read(body, "$.id"));
    }
}
