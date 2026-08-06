package com.campusguard.comment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateCommentRequest(
        /* Null for a top-level comment. */
        UUID parentCommentId,
        @NotBlank @Size(max = 10_000) String body) {
}
