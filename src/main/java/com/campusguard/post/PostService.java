package com.campusguard.post;

import com.campusguard.common.ContentRateLimitProperties;
import com.campusguard.common.PageCursor;
import com.campusguard.common.NotFoundException;
import com.campusguard.common.TooManyRequestsException;
import com.campusguard.media.MediaObject;
import com.campusguard.media.MediaService;
import com.campusguard.user.User;
import com.campusguard.user.UserRepository;
import com.campusguard.user.UserRole;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Services take the acting user's id as an ordinary argument rather than reading
 * it from a security context.
 *
 * <p>That keeps authentication a concern of the web layer: when JWT lands, only
 * the controller changes, and these methods stay directly unit-testable without
 * standing up a security context.
 */
@Service
public class PostService {

    private final PostRepository postRepository;
    private final UserRepository userRepository;
    private final ContentRateLimitProperties rateLimits;
    private final MediaService mediaService;

    public PostService(
            PostRepository postRepository,
            UserRepository userRepository,
            ContentRateLimitProperties rateLimits,
            MediaService mediaService) {
        this.rateLimits = rateLimits;
        this.postRepository = postRepository;
        this.userRepository = userRepository;
        this.mediaService = mediaService;
    }

    @Transactional
    public PostResponse create(UUID authorId, CreatePostRequest request) {
        requireWithinRateLimit(authorId);

        User author = userRepository
                .findById(authorId)
                .orElseThrow(() -> new NotFoundException("No user with id " + authorId));

        // Flushed rather than merely saved: @CreationTimestamp assigns createdAt
        // as the insert is prepared, so without a flush the response would carry
        // a null timestamp for a row that has one. Flushing here also surfaces
        // constraint violations inside this call instead of at commit.
        MediaObject media = request.mediaId() == null ? null : mediaService.requireOwned(request.mediaId(), authorId);
        Post post = postRepository.saveAndFlush(
                new Post(request.forumKey(), author, request.title(), request.body() == null ? "" : request.body(), media));

        return PostResponse.of(post);
    }

    /**
     * Mapping to DTOs happens inside the transaction on purpose. With
     * {@code open-in-view} disabled the persistence context closes when this
     * method returns, so handing entities to the controller would fail on the
     * first lazy association it touched.
     */
    @Transactional(readOnly = true)
    public FeedPage feed(String forumKey, String cursor, int size) {
        PageCursor from = cursor == null || cursor.isBlank() ? null : PageCursor.decode(cursor);

        // One row past the page. Reading it is how the answer to "is there more"
        // is obtained without a second query, and counting every live post in the
        // forum to render twenty of them would be work nobody reads.
        PageRequest lookahead = PageRequest.of(0, size + 1);
        List<Post> rows = from == null
                ? postRepository.findFeedFirstPage(forumKey, lookahead)
                : postRepository.findFeedAfter(forumKey, from.createdAt(), from.id(), lookahead);

        boolean hasMore = rows.size() > size;
        List<PostResponse> items = rows.stream().limit(size).map(PostResponse::of).toList();

        String next = hasMore && !items.isEmpty()
                ? new PageCursor(items.getLast().createdAt(), items.getLast().id()).encode()
                : null;

        return new FeedPage(items, hasMore, next);
    }

    @Transactional(readOnly = true)
    public PostResponse get(UUID id) {
        return postRepository
                .findLiveById(id)
                .map(PostResponse::of)
                .orElseThrow(() -> new NotFoundException("No post with id " + id));
    }

    @Transactional
    public PostResponse update(UUID authorId, UUID postId, UpdatePostRequest request) {
        Post post = postRepository
                .findLiveById(postId)
                .orElseThrow(() -> new NotFoundException("No post with id " + postId));
        if (!post.getAuthor().getId().equals(authorId)) {
            throw new AccessDeniedException("Only the author can edit this post.");
        }

        MediaObject media = request.mediaId() == null
                ? null
                : mediaService.requireOwned(request.mediaId(), authorId);
        post.update(request.title(), request.body() == null ? "" : request.body(), media);
        return PostResponse.of(post);
    }

    /**
     * Soft delete: the row survives so that reports already pointing at it still
     * resolve, and so a moderation decision remains auditable after the content
     * stops being visible.
     *
     * <p>The caller's role is re-read from the database instead of taken from the
     * token. A token issued before someone was demoted still carries the old role
     * until it expires, which is tolerable for reads and not tolerable for a
     * destructive action.
     *
     * <p>No explicit save: the entity is managed inside this transaction, so
     * Hibernate's dirty checking writes the timestamp at flush.
     */
    @Transactional
    public void delete(UUID actorId, UUID postId) {
        Post post = postRepository
                .findLiveById(postId)
                .orElseThrow(() -> new NotFoundException("No post with id " + postId));

        User actor = userRepository
                .findById(actorId)
                .orElseThrow(() -> new NotFoundException("No user with id " + actorId));

        boolean isAuthor = post.getAuthor().getId().equals(actorId);
        boolean isAdmin = actor.getRole() == UserRole.ADMIN;

        if (!isAuthor && !isAdmin) {
            throw new AccessDeniedException("Only the author or an administrator can delete this post.");
        }

        post.softDelete(Instant.now());
    }

    /**
     * A ceiling on flooding, not a throttle on enthusiasm.
     *
     * <p>Reporting was rate limited from the start and authoring was not, which
     * had it backwards. A report costs a moderator one glance at something a
     * person already flagged; a post that gets reported costs an engine call, a
     * queue slot and a reviewer's attention. Since anyone can register, being
     * signed in was the only thing standing between a script and the queue.
     */
    private void requireWithinRateLimit(UUID authorId) {
        Instant since = Instant.now().minus(rateLimits.window());
        long recent = postRepository.countByAuthorIdAndCreatedAtAfter(authorId, since);

        if (recent >= rateLimits.postsPerUser()) {
            throw new TooManyRequestsException(
                    "You have posted %d times in the last %s. Try again later."
                            .formatted(recent, rateLimits.window()));
        }
    }
}
