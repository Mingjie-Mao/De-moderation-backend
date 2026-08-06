package com.campusguard.comment;

import com.campusguard.common.AuthorView;
import com.campusguard.common.NotFoundException;
import com.campusguard.post.Post;
import com.campusguard.post.PostRepository;
import com.campusguard.user.User;
import com.campusguard.user.UserRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommentService {

    private final CommentRepository commentRepository;
    private final PostRepository postRepository;
    private final UserRepository userRepository;

    public CommentService(
            CommentRepository commentRepository,
            PostRepository postRepository,
            UserRepository userRepository) {
        this.commentRepository = commentRepository;
        this.postRepository = postRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public CommentResponse create(UUID postId, UUID authorId, CreateCommentRequest request) {
        Post post = postRepository
                .findLiveById(postId)
                .orElseThrow(() -> new NotFoundException("No post with id " + postId));

        User author = userRepository
                .findById(authorId)
                .orElseThrow(() -> new NotFoundException("No user with id " + authorId));

        Comment parent = null;
        if (request.parentCommentId() != null) {
            parent = commentRepository
                    .findLiveById(request.parentCommentId())
                    .orElseThrow(() -> new NotFoundException(
                            "No comment with id " + request.parentCommentId()));

            // Without this check a reply could be grafted onto a thread on a
            // different post, producing a comment that is unreachable from the
            // post it belongs to and orphaned under the one it points at.
            if (!parent.getPost().getId().equals(postId)) {
                throw new NotFoundException(
                        "Comment " + parent.getId() + " does not belong to post " + postId);
            }
        }

        // See PostService#create: the flush is what populates createdAt before
        // the response is built.
        Comment saved =
                commentRepository.saveAndFlush(new Comment(post, parent, author, request.body()));

        return new CommentResponse(
                saved.getId(),
                parent == null ? null : parent.getId(),
                AuthorView.of(author),
                saved.getBody(),
                saved.getCreatedAt(),
                List.of());
    }

    /**
     * One query for the whole thread, assembled into a tree in memory.
     *
     * <p>The alternative, a recursive CTE, wins on very deep threads but costs a
     * query that no longer maps onto an entity and is harder to read. A campus
     * thread is small; a single indexed read plus an in-memory pass is both
     * faster here and easier to reason about.
     */
    @Transactional(readOnly = true)
    public List<CommentResponse> thread(UUID postId) {
        if (!postRepository.existsByIdAndDeletedAtIsNull(postId)) {
            throw new NotFoundException("No post with id " + postId);
        }

        List<Comment> flat = commentRepository.findLiveByPostId(postId);

        Map<UUID, List<Comment>> childrenOf = new HashMap<>();
        List<Comment> roots = new ArrayList<>();
        for (Comment comment : flat) {
            Comment parent = comment.getParent();
            if (parent == null) {
                roots.add(comment);
            } else {
                // Reading the id off a lazy proxy does not initialise it, so this
                // stays within the single query above.
                childrenOf.computeIfAbsent(parent.getId(), key -> new ArrayList<>()).add(comment);
            }
        }

        return roots.stream().map(root -> toResponse(root, childrenOf)).toList();
    }

    private CommentResponse toResponse(Comment comment, Map<UUID, List<Comment>> childrenOf) {
        List<CommentResponse> replies = childrenOf.getOrDefault(comment.getId(), List.of()).stream()
                .map(child -> toResponse(child, childrenOf))
                .toList();

        Comment parent = comment.getParent();

        return new CommentResponse(
                comment.getId(),
                parent == null ? null : parent.getId(),
                AuthorView.of(comment.getAuthor()),
                comment.getBody(),
                comment.getCreatedAt(),
                replies);
    }
}
