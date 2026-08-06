package com.campusguard.post;

import com.campusguard.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/posts")
@Tag(name = "Posts", description = "Forum posts")
public class PostController {

    private final PostService postService;

    public PostController(PostService postService) {
        this.postService = postService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "bearer-jwt")
    @Operation(summary = "Create a post")
    public PostResponse create(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreatePostRequest request) {
        return postService.create(AuthenticatedUser.idOf(jwt), request);
    }

    @GetMapping
    @Operation(summary = "List live posts in a forum, newest first")
    public List<PostResponse> feed(
            @RequestParam("forum") String forumKey,
            @PageableDefault(size = 20) Pageable pageable) {
        return postService.feed(forumKey, pageable);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch a single live post")
    public PostResponse get(@PathVariable UUID id) {
        return postService.get(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirement(name = "bearer-jwt")
    @Operation(summary = "Soft-delete a post; permitted to its author or an administrator")
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        postService.delete(AuthenticatedUser.idOf(jwt), id);
    }
}
