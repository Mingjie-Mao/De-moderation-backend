package com.campusguard.auth;

import com.campusguard.user.User;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;

@Service
public class RefreshTokenService {

    private final RefreshTokenRepository repository;
    private final Duration ttl;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(
            RefreshTokenRepository repository,
            @Value("${campusguard.security.refresh-token-ttl:30d}") Duration ttl) {
        this.repository = repository;
        this.ttl = ttl;
    }

    public String issue(User user) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String raw = HexFormat.of().formatHex(bytes);
        repository.save(new RefreshToken(hash(raw), user, Instant.now().plus(ttl)));
        return raw;
    }

    /** Rotates on every use, so a stolen old token cannot be replayed indefinitely. */
    public User consume(String raw) {
        Instant now = Instant.now();
        RefreshToken token = repository
                .findByTokenHash(hash(raw))
                .filter(candidate -> candidate.usableAt(now))
                .orElseThrow(() -> new BadCredentialsException("The refresh token is invalid or expired."));
        token.revoke(now);
        return token.user();
    }

    public void revokeAll(UUID userId) {
        repository.deleteByUserId(userId);
    }

    private String hash(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BadCredentialsException("The refresh token is required.");
        }
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is required by the JVM.", ex);
        }
    }
}
