package com.campusguard;

import com.campusguard.security.TokenIssuer;
import com.campusguard.user.User;
import com.campusguard.user.UserRepository;
import com.campusguard.user.UserRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Integration tests run against a real PostgreSQL 16, the same major version the
 * application deploys on.
 *
 * <p>An in-memory database would be faster but would not enforce the partial
 * index, the check constraints or the unique index that this schema relies on
 * for correctness, so it would pass tests the real database would fail.
 *
 * <p>Because {@code ddl-auto} is set to validate, merely reaching a running
 * context here proves the JPA mappings and the Flyway migrations agree.
 *
 * <p>The container is a JVM-wide singleton started once and never stopped, rather
 * than one managed per test class: Spring caches the application context across
 * these classes, and a per-class container lifecycle would tear the database out
 * from under that cached context.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            // A fixed test key. Production has no default at all and refuses to
            // start without one; tests need determinism, not secrecy.
            "campusguard.security.jwt.secret=test-signing-key-that-is-long-enough-for-hs256",
            "campusguard.security.jwt.ttl=15m",
            // The queue is drained by calling the worker directly. A background
            // poll would race every assertion about what state a case is in, and
            // the resulting test would fail once in every few dozen runs.
            "campusguard.moderation.scheduler-enabled=false",
            // Authentication throttling is covered by its own focused tests.
            // The shared PostgreSQL container and MockMvc address would otherwise
            // make unrelated login tests consume one another's production quota.
            "campusguard.auth-rate-limit.registrations-per-ip=10000",
            "campusguard.auth-rate-limit.logins-per-ip=10000",
            "campusguard.auth-rate-limit.logins-per-account=10000",
            "campusguard.auth-rate-limit.refreshes-per-ip=10000"
        })
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    protected static final String RAW_PASSWORD = "correct-horse-battery";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @Autowired
    protected TokenIssuer tokenIssuer;

    @Autowired
    protected JwtEncoder jwtEncoder;

    /**
     * A fresh account per test. Tests share one database, so isolation comes from
     * each test owning its own actors and forum key rather than from rolling back
     * a surrounding transaction, which would also roll back the service-level
     * transactions being exercised.
     */
    protected User newUser() {
        return newUser(UserRole.MEMBER);
    }

    protected User newAdmin() {
        return newUser(UserRole.ADMIN);
    }

    protected User newUser(UserRole role) {
        String username = "user_" + UUID.randomUUID().toString().substring(0, 8);
        return userRepository.saveAndFlush(
                new User(username, passwordEncoder.encode(RAW_PASSWORD), role));
    }

    /** Tokens are minted directly rather than through the login endpoint, so that a test of posting is not also a test of logging in. */
    protected String bearer(User user) {
        return "Bearer " + tokenIssuer.issue(user);
    }

    /**
     * A correctly signed token whose subject is nobody. This is what an account
     * deleted after its token was issued looks like from the server's side, and
     * it is the only way to reach that path now that identity comes from a
     * signature rather than from a header the caller writes.
     */
    protected String bearerForUnknownUser() {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("campusguard")
                .issuedAt(now)
                .expiresAt(now.plus(15, ChronoUnit.MINUTES))
                .subject(UUID.randomUUID().toString())
                .claim("roles", List.of(UserRole.MEMBER.name()))
                .build();

        String token = jwtEncoder
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();

        return "Bearer " + token;
    }

    protected String uniqueForumKey() {
        return "forum_" + UUID.randomUUID().toString().substring(0, 8);
    }

    protected String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
