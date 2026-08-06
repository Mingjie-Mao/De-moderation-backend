package com.campusguard.comment;

import com.campusguard.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/posts/{postId}/comments")
@Tag(name = "Comments", description = "Threaded replies on a post")
public class CommentController {

    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "bearer-jwt")
    @Operation(summary = "Comment on a post, optionally as a reply to another comment")
    public CommentResponse create(
            @PathVariable UUID postId,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateCommentRequest request) {
        return commentService.create(postId, AuthenticatedUser.idOf(jwt), request);
    }

    @GetMapping
    @Operation(summary = "Fetch the full comment thread for a post")
    public List<CommentResponse> thread(@PathVariable UUID postId) {
        return commentService.thread(postId);
    }
}
