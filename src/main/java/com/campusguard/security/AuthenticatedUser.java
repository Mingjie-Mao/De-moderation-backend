package com.campusguard.security;

import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Pulls the acting user's id out of a verified token.
 *
 * <p>Controllers call this explicitly rather than having the id injected by a
 * custom resolver: the conversion from token to identity is the security-critical
 * step in every write path, and it is worth being able to see it.
 */
public final class AuthenticatedUser {

    private AuthenticatedUser() {
    }

    public static UUID idOf(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
