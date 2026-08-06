package com.campusguard.config;

import com.campusguard.user.User;
import com.campusguard.user.UserRepository;
import com.campusguard.user.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Scaffolding, not a feature.
 *
 * <p>There is no way to create an account yet, and every write endpoint needs an
 * existing user id, so local runs would otherwise start with a database nobody
 * can post to. Registration arrives with authentication and this class goes away
 * with it.
 *
 * <p>Seeding lives here rather than in a Flyway migration on purpose: migrations
 * describe schema, run in every environment, and would carry these accounts into
 * production. This is guarded by a property that is only ever true locally, and
 * the passwords are deliberately unusable placeholders.
 */
@Configuration
@ConditionalOnProperty(name = "campusguard.dev.seed-users", havingValue = "true")
public class DevUserSeeder {

    private static final Logger log = LoggerFactory.getLogger(DevUserSeeder.class);

    @Bean
    public ApplicationRunner seedDevUsers(UserRepository userRepository) {
        return args -> {
            if (userRepository.count() > 0) {
                logExistingUsers(userRepository);
                return;
            }

            User member = userRepository.save(new User("demo_member", "not-a-real-hash", UserRole.MEMBER));
            User reporter = userRepository.save(new User("demo_reporter", "not-a-real-hash", UserRole.MEMBER));
            User admin = userRepository.save(new User("demo_admin", "not-a-real-hash", UserRole.ADMIN));

            log.info("Seeded development users:");
            log.info("  member   {}  {}", member.getId(), member.getUsername());
            log.info("  reporter {}  {}", reporter.getId(), reporter.getUsername());
            log.info("  admin    {}  {}", admin.getId(), admin.getUsername());
        };
    }

    private void logExistingUsers(UserRepository userRepository) {
        log.info("Development users already present:");
        userRepository
                .findAll()
                .forEach(user -> log.info("  {}  {}  {}", user.getId(), user.getUsername(), user.getRole()));
    }
}
