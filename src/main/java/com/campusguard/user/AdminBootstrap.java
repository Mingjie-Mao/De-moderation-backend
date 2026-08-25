package com.campusguard.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first administrator, because nothing else can.
 *
 * <p>Registration only ever produces a {@code MEMBER}, and every route under
 * {@code /api/admin} requires {@code ROLE_ADMIN}, so a fresh database has a
 * moderation console nobody can open. This closes that gap without adding a
 * promotion endpoint, which would be a permanent privilege-granting route in
 * exchange for a problem that exists once per deployment.
 *
 * <p>It creates and never modifies. An existing account with the configured
 * name is left exactly as it is — same role, same password — because the
 * alternative is that setting {@code ADMIN_USERNAME} to a name somebody already
 * registered hands that person administrator rights, and that changing
 * {@code ADMIN_PASSWORD} silently rotates a live credential out from under
 * whoever holds it.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AdminProperties properties;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminBootstrap(
            AdminProperties properties, UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.configured()) {
            log.info(
                    "No administrator configured: campusguard.admin.username and .password are not both set. "
                            + "Nothing under /api/admin will be reachable until an administrator exists.");
            return;
        }

        String username = properties.username();

        if (userRepository.findByUsername(username).isPresent()) {
            // Deliberately not reporting whether it is an administrator. Either
            // way this component's answer is the same, and saying more would
            // put a fact about an existing account into the log on the strength
            // of a guessed name in configuration.
            log.info("Administrator bootstrap skipped: the account '{}' already exists.", username);
            return;
        }

        userRepository.saveAndFlush(
                new User(username, passwordEncoder.encode(properties.password()), UserRole.ADMIN));

        log.info("Created administrator '{}' from configuration.", username);
    }
}
