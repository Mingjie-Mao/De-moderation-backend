package com.campusguard.comment;

import jakarta.validation.constraints.Size;
import java.util.UUID;

@CommentContent
public record CreateCommentRequest(
        /* Null for a top-level comment. */
        UUID parentCommentId,
        @Size(max = 10_000) String body,
        UUID mediaId) {

    public CreateCommentRequest(UUID parentCommentId, String body) {
        this(parentCommentId, body, null);
    }
}
