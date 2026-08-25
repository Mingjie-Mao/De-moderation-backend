package com.campusguard.comment;

import java.util.List;

/**
 * A slice of a thread: some top-level comments, each with its replies nested
 * underneath, and whether there are more top-level comments after them.
 *
 * <p>Only the roots are paged. Paging the tree itself would hand a client
 * fragments it cannot assemble — a reply is meaningless without the comment it
 * answers — so the unit of a page is a whole conversation, and depth is bounded
 * separately by a check constraint.
 *
 * <p>Same shape as the feed's page on purpose. A client that already knows how to
 * follow {@code nextCursor} through a forum does not need to learn a second idea
 * to follow one through a thread.
 *
 * @param hasMore established by reading one root past the page and discarding it
 * @param nextCursor null on the last page
 */
public record CommentPage(List<CommentResponse> items, boolean hasMore, String nextCursor) {
}
