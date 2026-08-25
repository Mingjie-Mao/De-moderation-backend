package com.campusguard.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campusguard.AbstractIntegrationTest;
import com.campusguard.post.CreatePostRequest;
import com.campusguard.user.User;
import com.campusguard.user.UserRepository;
import com.campusguard.user.UserRole;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A ban has to bite now, not when the token happens to expire.
 *
 * <p>Reading a token's claims and stopping there is normal and, for most APIs,
 * fine. It is not fine here. Banning is the strongest thing the moderation
 * console does and it is pointed at somebody actively causing harm, so an hour
 * of continued posting after the decision is the failure the console exists to
 * prevent. Before the account check, a suspended user's pre-ban token created a
 * post and got a 201 back.
 *
 * <p>The demotion case is the same bug wearing a different hat, and worse: an
 * administrator who lost the role kept resolving cases and banning other people
 * until their token ran out.
 */
class AccountStateIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private UserRepository users;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactions;

    /**
     * Through the database, because {@link User} has no role setter on purpose:
     * no endpoint grants the administrator role, so a promotion really is an
     * {@code update} somebody runs by hand. Testing it any other way would test a
     * path production does not have.
     */
    private void setRole(User user, UserRole role) {
        transactions.executeWithoutResult(status -> entityManager
                .createQuery("update User u set u.role = :role where u.id = :id")
                .setParameter("role", role)
                .setParameter("id", user.getId())
                .executeUpdate());
    }

    @Test
    void aSuspendedAccountCannotActOnATokenIssuedBeforeTheBan() throws Exception {
        User member = newUser();
        String token = bearer(member);

        createPost(token).andExpect(status().isCreated());

        member.ban();
        users.saveAndFlush(member);

        createPost(token).andExpect(status().isUnauthorized());
    }

    /**
     * Deleting the row outright is treated the same as suspension. Either way the
     * token names somebody not entitled to act.
     */
    @Test
    void aDeletedAccountCannotActOnAnOutstandingToken() throws Exception {
        User member = newUser();
        String token = bearer(member);

        users.delete(member);
        users.flush();

        createPost(token).andExpect(status().isUnauthorized());
    }

    /**
     * Authority comes from the stored role, not from the claim, or a demotion
     * would be as slow to take effect as a ban was.
     */
    @Test
    void aDemotedAdministratorLosesTheConsoleImmediately() throws Exception {
        User admin = newAdmin();
        String token = bearer(admin);

        mockMvc.perform(get("/api/admin/moderation-cases").header("Authorization", token))
                .andExpect(status().isOk());

        setRole(admin, UserRole.MEMBER);

        mockMvc.perform(get("/api/admin/moderation-cases").header("Authorization", token))
                .andExpect(status().isForbidden());
    }

    /**
     * The other direction, so the check cannot be satisfied by refusing
     * everybody: a promotion is picked up on the token the user already holds.
     */
    @Test
    void aPromotedMemberGainsTheConsoleWithoutSigningInAgain() throws Exception {
        User member = newUser();
        String token = bearer(member);

        mockMvc.perform(get("/api/admin/moderation-cases").header("Authorization", token))
                .andExpect(status().isForbidden());

        setRole(member, UserRole.ADMIN);

        mockMvc.perform(get("/api/admin/moderation-cases").header("Authorization", token))
                .andExpect(status().isOk());
    }

    /** An ordinary active member is unaffected by any of this. */
    @Test
    void anActiveMemberIsLeftAlone() throws Exception {
        createPost(bearer(newUser())).andExpect(status().isCreated());
    }

    /** Reading the forum needs no token, so the check must not run on it. */
    @Test
    void publicReadsStillNeedNoToken() throws Exception {
        mockMvc.perform(get("/api/posts").param("forum", uniqueForumKey()))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions createPost(String token) throws Exception {
        return mockMvc.perform(post("/api/posts")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(new CreatePostRequest(uniqueForumKey(), "Title", "Ordinary enough content"))));
    }
}
