package com.campusguard.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param secret signing key, read from the environment. There is deliberately no
 *     default: a fallback secret is the kind of thing that ships to production
 *     unnoticed, and a JWT signed with a publicly known key is not authentication
 *     at all. Startup fails without it.
 * @param ttl how long an issued token stays valid. Short on purpose, because
 *     nothing revokes a token that has already been handed out.
 */
@ConfigurationProperties("campusguard.security.jwt")
public record JwtProperties(String secret, Duration ttl) {

    /** HS256 requires a key of at least 256 bits. */
    private static final int MINIMUM_SECRET_BYTES = 32;

    public JwtProperties {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "campusguard.security.jwt.secret is not set. Generate one with "
                            + "`openssl rand -hex 32` and expose it as JWT_SECRET.");
        }
        if (secret.getBytes().length < MINIMUM_SECRET_BYTES) {
            throw new IllegalStateException(
                    "campusguard.security.jwt.secret must be at least "
                            + MINIMUM_SECRET_BYTES
                            + " bytes for HS256; got "
                            + secret.getBytes().length
                            + ".");
        }
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalStateException("campusguard.security.jwt.ttl must be a positive duration.");
        }
    }
}
