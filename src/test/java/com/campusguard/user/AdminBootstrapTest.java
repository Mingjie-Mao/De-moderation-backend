package com.campusguard.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusguard.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The bootstrap is driven directly rather than through the application context.
 *
 * <p>Its inputs are configuration properties, and reaching every branch through
 * the context would mean a separate {@code @SpringBootTest} per combination —
 * five contexts, five container-backed startups, to test one {@code if}.
 * Constructing it with the real repository and encoder exercises the same code
 * against the same database.
 */
class AdminBootstrapTest extends AbstractIntegrationTest {

    private AdminBootstrap bootstrapFor(String username, String password) {
        return new AdminBootstrap(new AdminProperties(username, password), userRepository, passwordEncoder);
    }

    private String unusedUsername() {
        return "admin_" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void createsNothingWhenUnconfigured() {
        long before = userRepository.count();

        bootstrapFor("", "").run(null);

        assertThat(userRepository.count()).isEqualTo(before);
    }

    @Test
    void createsNothingWhenOnlyOneHalfIsSet() {
        String username = unusedUsername();

        bootstrapFor(username, "").run(null);
        bootstrapFor("", "some-password").run(null);

        assertThat(userRepository.findByUsername(username)).isEmpty();
    }

    @Test
    void createsNothingWhenTheValuesAreBlankRatherThanEmpty() {
        String username = unusedUsername();

        bootstrapFor(username, "   ").run(null);

        assertThat(userRepository.findByUsername(username)).isEmpty();
    }

    @Test
    void createsAnAdministratorForAnUnusedName() {
        String username = unusedUsername();

        bootstrapFor(username, "bootstrap-password").run(null);

        User created = userRepository.findByUsername(username).orElseThrow();
        assertThat(created.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(created.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(passwordEncoder.matches("bootstrap-password", created.getPasswordHash())).isTrue();
    }

    /**
     * The reason this component creates and never modifies. If configuring a name
     * somebody had already registered promoted that account, then anyone who
     * guessed the name a deployment would use could hold administrator rights by
     * registering it first.
     */
    @Test
    void doesNotPromoteAnExistingMember() {
        User member = newUser(UserRole.MEMBER);

        bootstrapFor(member.getUsername(), "bootstrap-password").run(null);

        User after = userRepository.findByUsername(member.getUsername()).orElseThrow();
        assertThat(after.getRole()).isEqualTo(UserRole.MEMBER);
        assertThat(after.getId()).isEqualTo(member.getId());
    }

    /** A changed ADMIN_PASSWORD must not rotate the credential of a live account. */
    @Test
    void doesNotRewriteAnExistingAdministratorsPassword() {
        User admin = newAdmin();
        String originalHash = admin.getPasswordHash();

        bootstrapFor(admin.getUsername(), "a-different-password").run(null);

        User after = userRepository.findByUsername(admin.getUsername()).orElseThrow();
        assertThat(after.getPasswordHash()).isEqualTo(originalHash);
        assertThat(passwordEncoder.matches(RAW_PASSWORD, after.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches("a-different-password", after.getPasswordHash())).isFalse();
    }

    @Test
    void isIdempotentAcrossRestarts() {
        String username = unusedUsername();

        bootstrapFor(username, "bootstrap-password").run(null);
        bootstrapFor(username, "bootstrap-password").run(null);

        assertThat(userRepository.findAll().stream().filter(u -> u.getUsername().equals(username)))
                .hasSize(1);
    }
}
