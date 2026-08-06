package com.campusguard.auth;

import java.util.UUID;

public record TokenResponse(
        String accessToken, String tokenType, long expiresInSeconds, UUID userId, String username) {

    static TokenResponse bearer(String token, long expiresInSeconds, UUID userId, String username) {
        return new TokenResponse(token, "Bearer", expiresInSeconds, userId, username);
    }
}
