package com.campusguard.post;

import com.campusguard.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/posts")
@Tag(name = "Posts", description = "Forum posts")
@Validated
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

    /**
     * @param cursor the {@code nextCursor} from the previous page, or absent for
     *     the first. Opaque on purpose: what is inside it is this service's
     *     business, and a client that parses it will break when that changes.
     */
    @GetMapping
    @Operation(summary = "List live posts in a forum, newest first")
    public FeedPage feed(
            @RequestParam("forum") String forumKey,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return postService.feed(forumKey, cursor, size);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch a single live post")
    public PostResponse get(@PathVariable UUID id) {
        return postService.get(id);
    }

    @PatchMapping("/{id}")
    @SecurityRequirement(name = "bearer-jwt")
    @Operation(summary = "Edit a post; permitted to its author")
    public PostResponse update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody UpdatePostRequest request) {
        return postService.update(AuthenticatedUser.idOf(jwt), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirement(name = "bearer-jwt")
    @Operation(summary = "Soft-delete a post; permitted to its author or an administrator")
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        postService.delete(AuthenticatedUser.idOf(jwt), id);
    }
}
