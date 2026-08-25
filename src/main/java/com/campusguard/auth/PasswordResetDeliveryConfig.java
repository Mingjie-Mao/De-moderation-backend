package com.campusguard.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

@Configuration
public class PasswordResetDeliveryConfig {

    @Bean
    @ConditionalOnProperty(name = "campusguard.mail.enabled", havingValue = "true")
    PasswordResetDelivery smtpPasswordResetDelivery(
            JavaMailSender sender,
            org.springframework.core.env.Environment environment) {
        String from = environment.getProperty("campusguard.mail.from", "no-reply@campusguard.local");
        String publicUrl = environment.getProperty("campusguard.public-url", "http://localhost:3000")
                .replaceFirst("/+$", "");
        java.time.Duration resetTtl = environment.getProperty(
                "campusguard.security.password-reset-ttl",
                java.time.Duration.class,
                java.time.Duration.ofMinutes(30));
        return (email, username, token) -> {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(email);
            message.setSubject("Reset your CampusGuard password");
            message.setText("Hello " + username + ",\n\nReset your password using this one-time link:\n"
                    + publicUrl + "/reset-password?token=" + token
                    + "\n\nThe link expires in " + resetTtl.toMinutes()
                    + " minutes. If you did not request it, ignore this email.");
            sender.send(message);
        };
    }

    @Bean
    @ConditionalOnMissingBean(PasswordResetDelivery.class)
    PasswordResetDelivery disabledPasswordResetDelivery() {
        // Enumeration-safe no-op. The public endpoint answers the same whether
        // the account is absent, lacks an email, or delivery is disabled.
        return (email, username, token) -> {};
    }
}
