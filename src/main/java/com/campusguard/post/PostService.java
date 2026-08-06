package com.campusguard.post;

import com.campusguard.common.NotFoundException;
import com.campusguard.user.User;
import com.campusguard.user.UserRepository;
import com.campusguard.user.UserRole;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
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

    public PostService(PostRepository postRepository, UserRepository userRepository) {
        this.postRepository = postRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public PostResponse create(UUID authorId, CreatePostRequest request) {
        User author = userRepository
                .findById(authorId)
                .orElseThrow(() -> new NotFoundException("No user with id " + authorId));

        // Flushed rather than merely saved: @CreationTimestamp assigns createdAt
        // as the insert is prepared, so without a flush the response would carry
        // a null timestamp for a row that has one. Flushing here also surfaces
        // constraint violations inside this call instead of at commit.
        Post post = postRepository.saveAndFlush(
                new Post(request.forumKey(), author, request.title(), request.body()));

        return PostResponse.of(post);
    }

    /**
     * Mapping to DTOs happens inside the transaction on purpose. With
     * {@code open-in-view} disabled the persistence context closes when this
     * method returns, so handing entities to the controller would fail on the
     * first lazy association it touched.
     */
    @Transactional(readOnly = true)
    public List<PostResponse> feed(String forumKey, Pageable pageable) {
        return postRepository.findFeed(forumKey, pageable).stream()
                .map(PostResponse::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public PostResponse get(UUID id) {
        return postRepository
                .findLiveById(id)
                .map(PostResponse::of)
                .orElseThrow(() -> new NotFoundException("No post with id " + id));
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
}
