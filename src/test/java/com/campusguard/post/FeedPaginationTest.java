package com.campusguard.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campusguard.AbstractIntegrationTest;
import com.campusguard.user.User;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Paging a feed that is still being written to.
 *
 * <p>Seeking past a known position rather than counting past an offset is the
 * whole point, and the test that matters is the one where a post arrives between
 * two requests: with offsets that shifts everything down and a reader silently
 * sees the same post twice while never seeing another.
 */
class FeedPaginationTest extends AbstractIntegrationTest {

    @Test
    void walksTheWholeFeedWithoutRepeatingOrSkipping() throws Exception {
        User author = newUser();
        String forumKey = uniqueForumKey();
        for (int i = 0; i < 7; i++) {
            createPost(author, forumKey, "Post " + i);
        }

        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;

        do {
            String body = fetchPage(forumKey, cursor, 3);
            seen.addAll(JsonPath.read(body, "$.items[*].title"));
            cursor = JsonPath.read(body, "$.nextCursor");
            pages++;
        } while (cursor != null && pages < 10);

        assertThat(seen).hasSize(7).doesNotHaveDuplicates();
        assertThat(seen).containsExactly("Post 6", "Post 5", "Post 4", "Post 3", "Post 2", "Post 1", "Post 0");
    }

    /**
     * The reason for a cursor rather than an offset. A post arriving between two
     * requests shifts every offset by one; a position does not move.
     */
    @Test
    void doesNotRepeatAPostWhenANewOneArrivesMidWayThrough() throws Exception {
        User author = newUser();
        String forumKey = uniqueForumKey();
        for (int i = 0; i < 4; i++) {
            createPost(author, forumKey, "Original " + i);
        }

        String firstPage = fetchPage(forumKey, null, 2);
        List<String> seen = new ArrayList<>(JsonPath.read(firstPage, "$.items[*].title"));
        String cursor = JsonPath.read(firstPage, "$.nextCursor");

        // Someone posts while the reader is between pages.
        createPost(author, forumKey, "Arrived late");

        seen.addAll(JsonPath.read(fetchPage(forumKey, cursor, 2), "$.items[*].title"));

        assertThat(seen).doesNotHaveDuplicates();
        assertThat(seen).containsExactly("Original 3", "Original 2", "Original 1", "Original 0");
        assertThat(seen).doesNotContain("Arrived late");
    }

    @Test
    void reportsNoMorePagesOnTheLastOne() throws Exception {
        User author = newUser();
        String forumKey = uniqueForumKey();
        createPost(author, forumKey, "Only one");

        mockMvc.perform(get("/api/posts").param("forum", forumKey).param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    /**
     * The lookahead row establishes hasMore, and must never be served: a page of
     * three that returned four would quietly break every client's layout.
     */
    @Test
    void neverServesTheLookaheadRow() throws Exception {
        User author = newUser();
        String forumKey = uniqueForumKey();
        for (int i = 0; i < 5; i++) {
            createPost(author, forumKey, "Post " + i);
        }

        mockMvc.perform(get("/api/posts").param("forum", forumKey).param("size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty());
    }

    /** A cursor is something this service issued; one that will not decode was invented. */
    @Test
    void rejectsACursorItNeverIssued() throws Exception {
        mockMvc.perform(get("/api/posts")
                        .param("forum", uniqueForumKey())
                        .param("cursor", "not-a-real-cursor"))
                .andExpect(status().isNotFound());
    }

    @Test
    void refusesAPageLargerThanTheCeiling() throws Exception {
        mockMvc.perform(get("/api/posts").param("forum", uniqueForumKey()).param("size", "5000"))
                .andExpect(status().isBadRequest());
    }

    private String fetchPage(String forumKey, String cursor, int size) throws Exception {
        var request = get("/api/posts").param("forum", forumKey).param("size", String.valueOf(size));
        if (cursor != null) {
            request = request.param("cursor", cursor);
        }
        return mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private void createPost(User author, String forumKey, String title) throws Exception {
        mockMvc.perform(post("/api/posts")
                        .header("Authorization", bearer(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreatePostRequest(forumKey, title, "Body"))))
                .andExpect(status().isCreated());
    }
}
