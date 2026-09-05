package com.campusguard.moderation;

import com.campusguard.comment.Comment;
import com.campusguard.comment.CommentRepository;
import com.campusguard.common.TargetType;
import com.campusguard.post.Post;
import com.campusguard.post.PostRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
     * The target's content whether or not it is still visible.
     *
     * <p>For the review console only. {@link #find} deliberately hides removed
     * content, which is right for an engine deciding what to judge and wrong for
     * an administrator looking at a decision already taken: reconsidering a hide
     * means reading the thing that was hidden, and "no longer available" is not
     * something anyone can review.
     */
    @Transactional(readOnly = true)
    public Optional<ModeratedContent> findIncludingRemoved(TargetType targetType, UUID targetId) {
        return switch (targetType) {
            case POST -> postRepository.findById(targetId).map(ContentLocator::from);
            case COMMENT -> commentRepository.findById(targetId).map(ContentLocator::from);
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
     * Every piece of content this author has written, as targets a case can point at.
     *
     * <p>The inverse of {@link #authorOf}, and the only way to get from a person
     * to their moderation history: cases are keyed by the content they concern,
     * so without this step there is no join between an author and the decisions
     * taken about them.
     *
     * <p>Deleted content is included, for the same reason
     * {@link #findIncludingRemoved} exists. A history assembled only from content
     * still on the site would omit exactly the items that were removed for
     * breaking a rule, which is to say all of the interesting ones.
     */
    @Transactional(readOnly = true)
    public List<TargetRef> targetsOf(UUID authorId) {
        List<TargetRef> refs = new ArrayList<>();
        postRepository.findAllIdsByAuthor(authorId)
                .forEach(id -> refs.add(new TargetRef(TargetType.POST, id)));
        commentRepository.findAllIdsByAuthor(authorId)
                .forEach(id -> refs.add(new TargetRef(TargetType.COMMENT, id)));
        return List.copyOf(refs);
    }

    /** A case's target, before it has been resolved to the content behind it. */
    public record TargetRef(TargetType type, UUID id) {
    }

    /**
     * Soft-delete the target.
     *
     * <p>Joins the caller's transaction rather than opening its own, which is what
     * makes hiding the content and closing the case one atomic decision. The
     * annotation is here to declare that a transaction is required, not to
     * suggest this is independent of the one around it.
     *
     * @return false when there was nothing live to hide, which happens when the
     *     author removed their own content between the report and the decision
     */
    @Transactional(propagation = Propagation.REQUIRED)
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

    /**
     * Undo a hide.
     *
     * <p>Looks the target up by id rather than through {@code findLiveById},
     * because the row this needs to reach is precisely the one that is not live.
     *
     * @return false when there was nothing hidden to restore
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean restore(TargetType targetType, UUID targetId) {
        return switch (targetType) {
            case POST -> postRepository
                    .findById(targetId)
                    .filter(Post::isDeleted)
                    .map(post -> {
                        post.restore();
                        return true;
                    })
                    .orElse(false);
            case COMMENT -> commentRepository
                    .findById(targetId)
                    .filter(comment -> comment.getDeletedAt() != null)
                    .map(comment -> {
                        comment.restore();
                        return true;
                    })
                    .orElse(false);
        };
    }

    private static ModeratedContent from(Post post) {
        return new ModeratedContent(
                TargetType.POST, post.getId(), post.getTitle(), post.getBody(), post.getAuthor().getId(),
                post.getMedia() == null ? null : post.getMedia().getId());
    }

    private static ModeratedContent from(Comment comment) {
        return new ModeratedContent(
                TargetType.COMMENT, comment.getId(), null, comment.getBody(), comment.getAuthor().getId(),
                comment.getMedia() == null ? null : comment.getMedia().getId());
    }

    public record ModeratedContent(
            TargetType targetType, UUID targetId, String title, String body, UUID authorId, UUID mediaId) {
    }
}
