package com.campusguard.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campusguard.AbstractIntegrationTest;
import com.campusguard.post.CreatePostRequest;
import com.campusguard.user.User;
import com.campusguard.user.UserRole;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

class AuthApiIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void registersAnAccountAndReturnsAUsableToken() throws Exception {
        String username = "newcomer_" + UUID.randomUUID().toString().substring(0, 8);

        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegisterRequest(username, RAW_PASSWORD))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.username").value(username))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String token = JsonPath.read(body, "$.accessToken");

        mockMvc.perform(post("/api/posts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreatePostRequest(uniqueForumKey(), "Hello", "First post"))))
                .andExpect(status().isCreated());
    }

    /**
     * Registration cannot ask for a role. An endpoint that grants privilege on
     * request grants it to whoever asks.
     */
    @Test
    void everyRegistrationLandsAsAPlainMember() throws Exception {
        String username = "plain_" + UUID.randomUUID().toString().substring(0, 8);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegisterRequest(username, RAW_PASSWORD))))
                .andExpect(status().isCreated());

        User created = userRepository.findByUsername(username).orElseThrow();
        assertThat(created.getRole()).isEqualTo(UserRole.MEMBER);
    }

    @Test
    void storesThePasswordOnlyAsABcryptHash() throws Exception {
        String username = "hashed_" + UUID.randomUUID().toString().substring(0, 8);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegisterRequest(username, RAW_PASSWORD))))
                .andExpect(status().isCreated());

        String stored = userRepository.findByUsername(username).orElseThrow().getPasswordHash();

        assertThat(stored).isNotEqualTo(RAW_PASSWORD);
        assertThat(stored).startsWith("$2");
        assertThat(passwordEncoder.matches(RAW_PASSWORD, stored)).isTrue();
    }

    @Test
    void refusesADuplicateUsername() throws Exception {
        String username = "twice_" + UUID.randomUUID().toString().substring(0, 8);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegisterRequest(username, RAW_PASSWORD))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegisterRequest(username, RAW_PASSWORD))))
                .andExpect(status().isConflict());
    }

    @Test
    void refusesAShortPassword() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new RegisterRequest("shorty_pw", "abc"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.password").isNotEmpty());
    }

    @Test
    void logsInWithCorrectCredentials() throws Exception {
        User user = newUser();

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginRequest(user.getUsername(), RAW_PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.userId").value(user.getId().toString()));
    }

    /**
     * A wrong password and an unknown account must be indistinguishable, or the
     * login endpoint becomes a way to find out who has an account here.
     */
    @Test
    void givesTheSameAnswerForAWrongPasswordAndAnUnknownAccount() throws Exception {
        User user = newUser();

        String wrongPassword = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginRequest(user.getUsername(), "not-the-password"))))
                .andExpect(status().isUnauthorized())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String unknownAccount = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginRequest("nobody_at_all", "not-the-password"))))
                .andExpect(status().isUnauthorized())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(JsonPath.read(wrongPassword, "$.detail").toString())
                .isEqualTo(JsonPath.read(unknownAccount, "$.detail").toString());
    }

    /**
     * Banning is a moderation action that does not exist yet, so the status is
     * set directly. What is under test is the login path's reaction to it.
     */
    @Test
    void refusesToIssueATokenToABannedAccount() throws Exception {
        User user = newUser();
        setStatus(user, "BANNED");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginRequest(user.getUsername(), RAW_PASSWORD))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Account unavailable"));
    }

    @Test
    void refusesToIssueATokenToASuspendedAccount() throws Exception {
        User user = newUser();
        setStatus(user, "SUSPENDED");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginRequest(user.getUsername(), RAW_PASSWORD))))
                .andExpect(status().isForbidden());
    }

    /**
     * Registration and login are the only routes that must work before a caller
     * holds a token, so they are the only unauthenticated write endpoints.
     */
    @Test
    void authRoutesAreReachableWithoutTheDocsBeingOpenToWrites() throws Exception {
        mockMvc.perform(get("/api/posts").param("forum", uniqueForumKey()))
                .andExpect(status().isOk());
    }

    private void setStatus(User user, String status) {
        jdbcTemplate.update("update users set status = ? where id = ?", status, user.getId());
    }
}
