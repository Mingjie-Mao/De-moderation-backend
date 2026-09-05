package com.campusguard.auth;

import java.util.UUID;

public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresInSeconds,
        UUID userId,
        String username) {

    static TokenResponse bearer(
            String token, String refreshToken, long expiresInSeconds, UUID userId, String username) {
        return new TokenResponse(token, refreshToken, "Bearer", expiresInSeconds, userId, username);
    }
}
