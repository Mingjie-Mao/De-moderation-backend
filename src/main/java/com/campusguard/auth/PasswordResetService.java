package com.campusguard.auth;

import com.campusguard.user.User;
import com.campusguard.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PasswordResetService {
    private final UserRepository users;
    private final PasswordResetTokenRepository tokens;
    private final PasswordResetDelivery delivery;
    private final PasswordEncoder passwords;
    private final RefreshTokenService refreshTokens;
    private final Duration ttl;
    private final SecureRandom random = new SecureRandom();

    public PasswordResetService(
            UserRepository users,
            PasswordResetTokenRepository tokens,
            PasswordResetDelivery delivery,
            PasswordEncoder passwords,
            RefreshTokenService refreshTokens,
            @Value("${campusguard.security.password-reset-ttl:30m}") Duration ttl) {
        this.users = users;
        this.tokens = tokens;
        this.delivery = delivery;
        this.passwords = passwords;
        this.refreshTokens = refreshTokens;
        this.ttl = ttl;
    }

    @Transactional
    public void request(String account) {
        User user = (account.contains("@")
                        ? users.findByEmailIgnoreCase(account.strip())
                        : users.findByUsername(account.strip()))
                .orElse(null);
        if (user == null || user.getEmail() == null) return;

        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String raw = HexFormat.of().formatHex(bytes);
        tokens.save(new PasswordResetToken(hash(raw), user, Instant.now().plus(ttl)));
        delivery.send(user.getEmail(), user.getUsername(), raw);
    }

    @Transactional
    public void confirm(PasswordResetConfirmRequest request) {
        Instant now = Instant.now();
        PasswordResetToken token = tokens.findByTokenHash(hash(request.token()))
                .filter(candidate -> candidate.usableAt(now))
                .orElseThrow(() -> new BadCredentialsException("The password reset token is invalid or expired."));
        User user = token.user();
        user.changePassword(passwords.encode(request.newPassword()));
        refreshTokens.revokeAll(user.getId());
        token.use(now);
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is required by the JVM.", ex);
        }
    }
}
