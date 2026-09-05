package com.campusguard.comment;

import jakarta.validation.constraints.Size;
import java.util.UUID;

@CommentContent
public record UpdateCommentRequest(
        @Size(max = 10_000) String body,
        UUID mediaId) {}
