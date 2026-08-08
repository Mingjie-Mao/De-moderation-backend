package com.campusguard.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
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
import org.springframework.test.context.TestPropertySource;

/**
 * The two ways a thread could grow without limit, and what stops each.
 *
 * <p>Depth was the sharper one. Replying to a reply had no ceiling and the read
 * path recurses once per level, so a chain of eight thousand answered the public
 * comments endpoint with a StackOverflowError — measured, on a real server, by
 * one account replying to itself. The ceiling lives in a check constraint so it
 * binds every writer rather than only the service that happens to write comments
 * today.
 *
 * <p>Width is the milder one: a thread with a hundred thousand top-level comments
 * is not a crash, only a response nobody can use. Roots are paged; a root's
 * replies come with it, because half a conversation is not something a client can
 * reassemble.
 */
@TestPropertySource(properties = {
    "campusguard.content.comments-per-user=500",
    "campusguard.content.posts-per-user=500"
})
class CommentThreadBoundsTest extends AbstractIntegrationTest {

    @Test
    void acceptsRepliesUpToTheCeiling() throws Exception {
        User author = newUser();
        UUID postId = createPost(author);

        UUID parent = comment(author, postId, null);
        for (int depth = 1; depth <= Comment.MAX_DEPTH; depth++) {
            parent = comment(author, postId, parent);
        }

        // The root plus MAX_DEPTH levels beneath it all stored.
        mockMvc.perform(get("/api/posts/{id}/comments", postId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)));
    }

    @Test
    void refusesTheReplyThatWouldGoDeeper() throws Exception {
        User author = newUser();
        UUID postId = createPost(author);

        UUID parent = comment(author, postId, null);
        for (int depth = 1; depth <= Comment.MAX_DEPTH; depth++) {
            parent = comment(author, postId, parent);
        }

        // A sentence somebody can act on, not a constraint violation as a 500.
        reply(author, postId, parent)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.containsString("Reply further up the thread")));
    }

    /** The ceiling is on nesting, not on how many replies one comment may have. */
    @Test
    void doesNotLimitHowWideOneCommentCanBe() throws Exception {
        User author = newUser();
        UUID postId = createPost(author);
        UUID root = comment(author, postId, null);

        for (int i = 0; i < 15; i++) {
            reply(author, postId, root).andExpect(status().isCreated());
        }

        mockMvc.perform(get("/api/posts/{id}/comments", postId))
                .andExpect(jsonPath("$.items[0].replies", hasSize(15)));
    }

    @Test
    void pagesTopLevelCommentsAndCarriesTheirRepliesAlong() throws Exception {
        User author = newUser();
        UUID postId = createPost(author);

        for (int i = 0; i < 5; i++) {
            UUID root = comment(author, postId, null);
            reply(author, postId, root).andExpect(status().isCreated());
        }

        String first = mockMvc.perform(get("/api/posts/{id}/comments", postId).param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.hasMore").value(true))
                // A root arrives with its subtree; paging never splits one.
                .andExpect(jsonPath("$.items[0].replies", hasSize(1)))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String cursor = JsonPath.read(first, "$.nextCursor");
        assertThat(cursor).isNotBlank();

        mockMvc.perform(get("/api/posts/{id}/comments", postId).param("size", "2").param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.hasMore").value(true));
    }

    /** The last page says so, and offers no cursor to keep going with. */
    @Test
    void theFinalPageEndsTheWalk() throws Exception {
        User author = newUser();
        UUID postId = createPost(author);
        comment(author, postId, null);
        comment(author, postId, null);

        mockMvc.perform(get("/api/posts/{id}/comments", postId).param("size", "20"))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()));
    }

    /** A thread reads in the order it happened, which is the opposite of the feed. */
    @Test
    void rootsComeBackOldestFirst() throws Exception {
        User author = newUser();
        UUID postId = createPost(author);

        UUID first = comment(author, postId, null);
        comment(author, postId, null);

        mockMvc.perform(get("/api/posts/{id}/comments", postId))
                .andExpect(jsonPath("$.items[0].id").value(first.toString()));
    }

    @Test
    void refusesACursorItNeverIssued() throws Exception {
        UUID postId = createPost(newUser());

        mockMvc.perform(get("/api/posts/{id}/comments", postId).param("cursor", "not-a-cursor"))
                .andExpect(status().isNotFound());
    }

    private UUID comment(User author, UUID postId, UUID parent) throws Exception {
        String body = reply(author, postId, parent)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private org.springframework.test.web.servlet.ResultActions reply(User author, UUID postId, UUID parent)
            throws Exception {
        return mockMvc.perform(post("/api/posts/{id}/comments", postId)
                .header("Authorization", bearer(author))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(new CreateCommentRequest(parent, "A reply"))));
    }

    private UUID createPost(User author) throws Exception {
        String body = mockMvc.perform(post("/api/posts")
                        .header("Authorization", bearer(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreatePostRequest(uniqueForumKey(), "Title", "Body text here"))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }
}
