package com.campusguard.post;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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

    /**
     * The acting user arrives as a header until authentication exists. Making it
     * an explicit parameter rather than hiding it behind a resolver keeps the
     * temporary nature of it visible in the signature and in Swagger, and the
     * swap to an authenticated principal touches this line only.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a post")
    public PostResponse create(
            @RequestHeader("X-User-Id") UUID actorId,
            @Valid @RequestBody CreatePostRequest request) {
        return postService.create(actorId, request);
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
}
