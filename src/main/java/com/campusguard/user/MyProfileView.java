package com.campusguard.user;

import java.time.Instant;
import java.util.UUID;

public record MyProfileView(
        UUID id,
        String username,
        String email,
        String displayName,
        String bio,
        UserRole role,
        UserStatus status,
        Instant createdAt) {

    static MyProfileView of(User user) {
        return new MyProfileView(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getDisplayName(),
                user.getBio(),
                user.getRole(),
                user.getStatus(),
                user.getCreatedAt());
    }
}
