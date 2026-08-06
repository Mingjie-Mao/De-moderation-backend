package com.campusguard.common;

import com.campusguard.user.User;
import java.util.UUID;

/**
 * The only projection of a user that ever crosses the API boundary.
 *
 * <p>Shared by posts and comments so that no response path can accidentally
 * widen into leaking a password hash or an account's moderation status.
 */
public record AuthorView(UUID id, String username) {

    public static AuthorView of(User user) {
        return new AuthorView(user.getId(), user.getUsername());
    }
}
