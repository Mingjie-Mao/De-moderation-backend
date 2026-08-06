package com.campusguard.comment;

import com.campusguard.common.AuthorView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A comment together with its replies. The nesting mirrors what a client renders,
 * so the client never has to reassemble a thread from a flat list itself.
 */
public record CommentResponse(
        UUID id,
        UUID parentCommentId,
        AuthorView author,
        String body,
        Instant createdAt,
        List<CommentResponse> replies) {
}
