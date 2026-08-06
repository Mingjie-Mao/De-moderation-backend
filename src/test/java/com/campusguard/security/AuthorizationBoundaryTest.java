package com.campusguard.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campusguard.AbstractIntegrationTest;
import com.campusguard.common.TargetType;
import com.campusguard.post.CreatePostRequest;
import com.campusguard.report.CreateReportRequest;
import com.campusguard.report.ReportReason;
import com.campusguard.user.User;
import com.campusguard.user.UserRole;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * The rejection paths. These are the tests that would still matter if every
 * happy path were deleted, because an authorisation bug is silent: everything
 * keeps working, just for the wrong people.
 */
class AuthorizationBoundaryTest extends AbstractIntegrationTest {

    @Test
    void readingTheForumNeedsNoToken() throws Exception {
        mockMvc.perform(get("/api/posts").param("forum", uniqueForumKey()))
                .andExpect(status().isOk());
    }

    @Test
    void writingWithoutATokenIsRejected() throws Exception {
        mockMvc.perform(post("/api/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreatePostRequest(uniqueForumKey(), "Title", "Body"))))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Authentication required"));
    }

    @Test
    void reportingWithoutATokenIsRejected() throws Exception {
        mockMvc.perform(post("/api/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateReportRequest(
                                TargetType.POST, UUID.randomUUID(), ReportReason.SPAM))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aTokenThatIsNotAJwtIsRejected() throws Exception {
        mockMvc.perform(post("/api/posts")
                        .header("Authorization", "Bearer not-a-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreatePostRequest(uniqueForumKey(), "Title", "Body"))))
                .andExpect(status().isUnauthorized());
    }

    /**
     * The claims are well formed and the structure is valid; only the signature
     * is wrong. If this passed, anyone could mint themselves any identity.
     */
    @Test
    void aTokenSignedWithTheWrongKeyIsRejected() throws Exception {
        SecretKey foreignKey = new SecretKeySpec(
                "a-completely-different-signing-key-for-tests".getBytes(), "HmacSHA256");
        JwtEncoder foreignEncoder = new NimbusJwtEncoder(new ImmutableSecret<>(foreignKey));

        User victim = newUser();
        String forged = sign(foreignEncoder, victim.getId(), Instant.now().plus(15, ChronoUnit.MINUTES));

        mockMvc.perform(post("/api/posts")
                        .header("Authorization", "Bearer " + forged)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreatePostRequest(uniqueForumKey(), "Title", "Body"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anExpiredTokenIsRejected() throws Exception {
        User user = newUser();
        String expired = sign(jwtEncoder, user.getId(), Instant.now().minus(1, ChronoUnit.MINUTES));

        mockMvc.perform(post("/api/posts")
                        .header("Authorization", "Bearer " + expired)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreatePostRequest(uniqueForumKey(), "Title", "Body"))))
                .andExpect(status().isUnauthorized());
    }

    /**
     * A member cannot promote themselves by asserting a role in their own token,
     * because the token is not theirs to write. This checks the other half: even
     * a legitimately signed token claiming ADMIN does not survive, since the
     * signing key is not available to a caller.
     */
    @Test
    void aForgedAdminClaimIsRejected() throws Exception {
        SecretKey foreignKey = new SecretKeySpec(
                "yet-another-key-that-the-server-does-not-know".getBytes(), "HmacSHA256");
        JwtEncoder foreignEncoder = new NimbusJwtEncoder(new ImmutableSecret<>(foreignKey));

        Instant expiry = Instant.now().plus(15, ChronoUnit.MINUTES);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("campusguard")
                .issuedAt(Instant.now())
                .expiresAt(expiry)
                .subject(UUID.randomUUID().toString())
                .claim("roles", List.of(UserRole.ADMIN.name()))
                .build();

        String forged = foreignEncoder
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();

        mockMvc.perform(post("/api/posts")
                        .header("Authorization", "Bearer " + forged)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreatePostRequest(uniqueForumKey(), "Title", "Body"))))
                .andExpect(status().isUnauthorized());
    }

    private String sign(JwtEncoder encoder, UUID subject, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("campusguard")
                .issuedAt(Instant.now().minus(2, ChronoUnit.MINUTES))
                .expiresAt(expiresAt)
                .subject(subject.toString())
                .claim("roles", List.of(UserRole.MEMBER.name()))
                .build();

        return encoder
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
