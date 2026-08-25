package com.campusguard.user;

import java.time.Instant;
import java.util.UUID;

public record UserView(
        UUID id,
        String username,
        String displayName,
        String bio,
        UserRole role,
        Instant createdAt) {

    static UserView of(User user) {
        return new UserView(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getBio(),
                user.getRole(),
                user.getCreatedAt());
    }
}
