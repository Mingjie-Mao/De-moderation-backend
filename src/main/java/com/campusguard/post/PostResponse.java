package com.campusguard.post;

import com.campusguard.common.AuthorView;
import java.time.Instant;
import java.util.UUID;

public record PostResponse(
        UUID id,
        String forumKey,
        String title,
        String body,
        AuthorView author,
        String mediaUrl,
        Instant createdAt) {

    public PostResponse(UUID id, String forumKey, String title, String body, AuthorView author, Instant createdAt) {
        this(id, forumKey, title, body, author, null, createdAt);
    }

    static PostResponse of(Post post) {
        return new PostResponse(
                post.getId(),
                post.getForumKey(),
                post.getTitle(),
                post.getBody(),
                AuthorView.of(post.getAuthor()),
                post.getMedia() == null ? null : "/api/media/" + post.getMedia().getId(),
                post.getCreatedAt());
    }
}
