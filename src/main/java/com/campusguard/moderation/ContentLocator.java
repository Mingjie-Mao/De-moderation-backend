package com.campusguard.moderation;

import com.campusguard.comment.Comment;
import com.campusguard.comment.CommentRepository;
import com.campusguard.common.TargetType;
import com.campusguard.post.Post;
import com.campusguard.post.PostRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a {@code (type, id)} pair into the content behind it.
 *
 * <p>This is the price of reports and cases pointing at a bare id instead of
 * carrying a foreign key: the branch on target type has to live somewhere. Here
 * is one place, rather than in the worker, the admin service and every future
 * caller.
 */
@Service
public class ContentLocator {

    private final PostRepository postRepository;
    private final CommentRepository commentRepository;

    public ContentLocator(PostRepository postRepository, CommentRepository commentRepository) {
        this.postRepository = postRepository;
        this.commentRepository = commentRepository;
    }

    @Transactional(readOnly = true)
    public Optional<ModeratedContent> find(TargetType targetType, UUID targetId) {
        return switch (targetType) {
            case POST -> postRepository.findLiveById(targetId).map(ContentLocator::from);
            case COMMENT -> commentRepository.findLiveById(targetId).map(ContentLocator::from);
        };
    }

    /**
     * The author of the target whether or not it is still visible.
     *
     * <p>Separate from {@link #find} because banning an author has to work on
     * content that has already been hidden, and {@code find} deliberately only
     * returns live content.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> authorOf(TargetType targetType, UUID targetId) {
        return switch (targetType) {
            case POST -> postRepository.findById(targetId).map(post -> post.getAuthor().getId());
            case COMMENT -> commentRepository.findById(targetId).map(comment -> comment.getAuthor().getId());
        };
    }

    /**
     * Soft-delete the target.
     *
     * @return false when there was nothing live to hide, which happens when the
     *     author removed their own content between the report and the decision
     */
    @Transactional
    public boolean hide(TargetType targetType, UUID targetId) {
        Instant now = Instant.now();
        return switch (targetType) {
            case POST -> postRepository
                    .findLiveById(targetId)
                    .map(post -> {
                        post.softDelete(now);
                        return true;
                    })
                    .orElse(false);
            case COMMENT -> commentRepository
                    .findLiveById(targetId)
                    .map(comment -> {
                        comment.softDelete(now);
                        return true;
                    })
                    .orElse(false);
        };
    }

    private static ModeratedContent from(Post post) {
        return new ModeratedContent(
                TargetType.POST, post.getId(), post.getTitle(), post.getBody(), post.getAuthor().getId());
    }

    private static ModeratedContent from(Comment comment) {
        return new ModeratedContent(
                TargetType.COMMENT, comment.getId(), null, comment.getBody(), comment.getAuthor().getId());
    }

    public record ModeratedContent(
            TargetType targetType, UUID targetId, String title, String body, UUID authorId) {
    }
}
