package com.campusguard.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campusguard.AbstractIntegrationTest;
import com.campusguard.comment.CreateCommentRequest;
import com.campusguard.post.CreatePostRequest;
import com.campusguard.user.User;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * A ceiling on how fast one account can fill the queue.
 *
 * <p>Reporting was limited from the first version and authoring was not, which
 * had the asymmetry backwards. A report costs a moderator one glance at
 * something a person already flagged; a post costs an engine call and a queue
 * slot the moment anyone reports it. Registration is open, so being signed in
 * ruled out nobody.
 *
 * <p>The limits are set low here and left generous in production. What is being
 * tested is that the ceiling exists and that the refusal is legible, not the
 * number itself.
 */
@TestPropertySource(properties = {
    "campusguard.content.posts-per-user=3",
    "campusguard.content.comments-per-user=2",
    "campusguard.content.window=1h"
})
class ContentRateLimitIntegrationTest extends AbstractIntegrationTest {

    @Test
    void refusesTheAccountThatKeepsPosting() throws Exception {
        User author = newUser();
        String forum = uniqueForumKey();

        for (int i = 0; i < 3; i++) {
            createPost(author, forum).andExpect(status().isCreated());
        }

        createPost(author, forum)
                .andExpect(status().isTooManyRequests())
                // The backend's own sentence, not a bare status: a member who hits
                // this should be able to tell it apart from the server breaking.
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("posted 3 times")));
    }

    @Test
    void refusesTheAccountThatKeepsCommenting() throws Exception {
        User author = newUser();
        UUID postId = createdPostId(newUser());

        for (int i = 0; i < 2; i++) {
            comment(author, postId).andExpect(status().isCreated());
        }

        comment(author, postId).andExpect(status().isTooManyRequests());
    }

    /**
     * Per account, not per forum or per instance. A limit somebody can reset by
     * moving to the next forum is not a limit.
     */
    @Test
    void theLimitFollowsTheAccountAcrossForums() throws Exception {
        User author = newUser();

        for (int i = 0; i < 3; i++) {
            createPost(author, uniqueForumKey()).andExpect(status().isCreated());
        }

        createPost(author, uniqueForumKey()).andExpect(status().isTooManyRequests());
    }

    /** One noisy account must not stop everybody else from posting. */
    @Test
    void oneAccountHittingTheCeilingDoesNotAffectAnother() throws Exception {
        User noisy = newUser();
        String forum = uniqueForumKey();

        for (int i = 0; i < 3; i++) {
            createPost(noisy, forum).andExpect(status().isCreated());
        }
        createPost(noisy, forum).andExpect(status().isTooManyRequests());

        createPost(newUser(), forum).andExpect(status().isCreated());
    }

    /** Posting and commenting are counted separately, so one cannot exhaust the other. */
    @Test
    void theTwoLimitsAreIndependent() throws Exception {
        User author = newUser();
        UUID postId = createdPostId(newUser());

        for (int i = 0; i < 2; i++) {
            comment(author, postId).andExpect(status().isCreated());
        }
        comment(author, postId).andExpect(status().isTooManyRequests());

        createPost(author, uniqueForumKey()).andExpect(status().isCreated());
    }

    private org.springframework.test.web.servlet.ResultActions createPost(User author, String forum)
            throws Exception {
        return mockMvc.perform(post("/api/posts")
                .header("Authorization", bearer(author))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(new CreatePostRequest(forum, "Title", "Ordinary enough content"))));
    }

    private org.springframework.test.web.servlet.ResultActions comment(User author, UUID postId) throws Exception {
        return mockMvc.perform(post("/api/posts/{id}/comments", postId)
                .header("Authorization", bearer(author))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(new CreateCommentRequest(null, "Ordinary enough reply"))));
    }

    private UUID createdPostId(User author) throws Exception {
        String body = createPost(author, uniqueForumKey())
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }
}
