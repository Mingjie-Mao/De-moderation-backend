package com.campusguard.comment;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campusguard.AbstractIntegrationTest;
import com.campusguard.post.CreatePostRequest;
import com.campusguard.user.User;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class CommentApiIntegrationTest extends AbstractIntegrationTest {

    @Test
    void nestsRepliesUnderTheirParent() throws Exception {
        User author = newUser();
        UUID postId = createPost(author);

        UUID topLevel = createComment(author, postId, null, "Top level");
        createComment(author, postId, topLevel, "A reply");
        createComment(author, postId, null, "Another top level");

        mockMvc.perform(get("/api/posts/{postId}/comments", postId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].body").value("Top level"))
                .andExpect(jsonPath("$.items[0].parentCommentId").value(nullValue()))
                .andExpect(jsonPath("$.items[0].replies", hasSize(1)))
                .andExpect(jsonPath("$.items[0].replies[0].body").value("A reply"))
                .andExpect(jsonPath("$.items[0].replies[0].parentCommentId").value(topLevel.toString()))
                .andExpect(jsonPath("$.items[1].body").value("Another top level"))
                .andExpect(jsonPath("$.items[1].replies", hasSize(0)));
    }

    @Test
    void nestsRepliesUpToTheCeiling() throws Exception {
        User author = newUser();
        UUID postId = createPost(author);

        UUID first = createComment(author, postId, null, "Depth 1");
        UUID second = createComment(author, postId, first, "Depth 2");
        createComment(author, postId, second, "Depth 3");

        mockMvc.perform(get("/api/posts/{postId}/comments", postId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].replies[0].replies[0].body").value("Depth 3"));
    }

    /**
     * A reply whose parent lives on another post would be unreachable from the
     * post it claims to belong to, so the pairing is rejected rather than stored.
     */
    @Test
    void rejectsAReplyWhoseParentBelongsToAnotherPost() throws Exception {
        User author = newUser();
        UUID postId = createPost(author);
        UUID otherPostId = createPost(author);

        UUID parentOnOtherPost = createComment(author, otherPostId, null, "Elsewhere");

        mockMvc.perform(post("/api/posts/{postId}/comments", postId)
                        .header("Authorization", bearer(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateCommentRequest(parentOnOtherPost, "Orphan"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsABlankBody() throws Exception {
        User author = newUser();
        UUID postId = createPost(author);

        mockMvc.perform(post("/api/posts/{postId}/comments", postId)
                        .header("Authorization", bearer(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateCommentRequest(null, "   "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.body").isNotEmpty());
    }

    @Test
    void returnsNotFoundForCommentsOnAnUnknownPost() throws Exception {
        mockMvc.perform(get("/api/posts/{postId}/comments", UUID.randomUUID()))
                .andExpect(status().isNotFound());
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

    private UUID createComment(User author, UUID postId, UUID parentId, String text) throws Exception {
        String body = mockMvc.perform(post("/api/posts/{postId}/comments", postId)
                        .header("Authorization", bearer(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateCommentRequest(parentId, text))))
                .andExpect(status().isCreated())
                // The create response must carry the persisted timestamp, not a
                // null placeholder that only fills in on a later read.
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return UUID.fromString(JsonPath.read(body, "$.id"));
    }
}
