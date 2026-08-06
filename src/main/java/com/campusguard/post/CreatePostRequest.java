package com.campusguard.post;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreatePostRequest(
        @NotBlank @Size(max = 50) String forumKey,
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 20_000) String body) {
}
