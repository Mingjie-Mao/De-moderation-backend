package com.campusguard;

import com.campusguard.user.User;
import com.campusguard.user.UserRepository;
import com.campusguard.user.UserRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
@TestPropertySource(properties = "campusguard.dev.seed-users=false")
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected UserRepository userRepository;

    /**
     * A fresh account per test. Tests share one database, so isolation comes from
     * each test owning its own actors and forum key rather than from rolling back
     * a surrounding transaction, which would also roll back the service-level
     * transactions being exercised.
     */
    protected User newUser() {
        String username = "user_" + UUID.randomUUID().toString().substring(0, 8);
        return userRepository.save(new User(username, "not-a-real-hash", UserRole.MEMBER));
    }

    protected String uniqueForumKey() {
        return "forum_" + UUID.randomUUID().toString().substring(0, 8);
    }

    protected String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
