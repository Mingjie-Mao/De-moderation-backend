package com.campusguard.post;

import com.campusguard.common.NotFoundException;
import com.campusguard.user.User;
import com.campusguard.user.UserRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
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
}
