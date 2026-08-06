package com.campusguard.post;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campusguard.AbstractIntegrationTest;
import com.campusguard.user.User;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class PostApiIntegrationTest extends AbstractIntegrationTest {

    @Test
    void createsAPostAndReturnsItInTheForumFeed() throws Exception {
        User author = newUser();
        String forumKey = uniqueForumKey();

        mockMvc.perform(post("/api/posts")
                        .header("Authorization", bearer(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreatePostRequest(forumKey, "Lost keys", "Near the library."))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.forumKey").value(forumKey))
                .andExpect(jsonPath("$.title").value("Lost keys"))
                .andExpect(jsonPath("$.author.username").value(author.getUsername()))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());

        mockMvc.perform(get("/api/posts").param("forum", forumKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].title").value("Lost keys"));
    }

    /**
     * The author is never serialised as a full user, so a password hash cannot
     * reach a client even if the entity gains new fields later.
     */
    @Test
    void doesNotExposeCredentialsOnTheAuthor() throws Exception {
        User author = newUser();
        String forumKey = uniqueForumKey();

        mockMvc.perform(post("/api/posts")
                        .header("Authorization", bearer(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreatePostRequest(forumKey, "Title", "Body"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.author.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.author.status").doesNotExist())
                .andExpect(jsonPath("$.author.role").doesNotExist());
    }

    @Test
    void feedIsNewestFirst() throws Exception {
        User author = newUser();
        String forumKey = uniqueForumKey();

        createPost(author, forumKey, "First");
        createPost(author, forumKey, "Second");

        mockMvc.perform(get("/api/posts").param("forum", forumKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].title").value("Second"))
                .andExpect(jsonPath("$[1].title").value("First"));
    }

    @Test
    void feedIsScopedToOneForum() throws Exception {
        User author = newUser();
        String mine = uniqueForumKey();
        String other = uniqueForumKey();

        createPost(author, mine, "Mine");
        createPost(author, other, "Theirs");

        mockMvc.perform(get("/api/posts").param("forum", mine))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].title").value("Mine"));
    }

    @Test
    void rejectsABlankTitleWithFieldLevelDetail() throws Exception {
        User author = newUser();

        mockMvc.perform(post("/api/posts")
                        .header("Authorization", bearer(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreatePostRequest(uniqueForumKey(), "  ", "Body"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.title").isNotEmpty());
    }

    @Test
    void returnsNotFoundForAnUnknownPost() throws Exception {
        mockMvc.perform(get("/api/posts/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource not found"));
    }

    /**
     * A correctly signed token outlives the account it was issued for. The
     * signature verifies, so the request gets past authentication and only fails
     * when the service goes looking for the user.
     */
    @Test
    void returnsNotFoundWhenTheTokenOutlivesItsAccount() throws Exception {
        mockMvc.perform(post("/api/posts")
                        .header("Authorization", bearerForUnknownUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreatePostRequest(uniqueForumKey(), "Title", "Body"))))
                .andExpect(status().isNotFound());
    }

    private void createPost(User author, String forumKey, String title) throws Exception {
        mockMvc.perform(post("/api/posts")
                        .header("Authorization", bearer(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreatePostRequest(forumKey, title, "Body"))))
                .andExpect(status().isCreated());
    }
}
