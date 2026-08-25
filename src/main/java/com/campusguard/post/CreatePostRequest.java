package com.campusguard.post;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreatePostRequest(
        @NotBlank @Size(max = 50) String forumKey,
        @NotBlank @Size(max = 200) String title,
        @Size(max = 20_000) String body,
        java.util.UUID mediaId) {

    public CreatePostRequest(String forumKey, String title, String body) {
        this(forumKey, title, body, null);
    }

    public CreatePostRequest {
        body = body == null ? "" : body;
    }
}
