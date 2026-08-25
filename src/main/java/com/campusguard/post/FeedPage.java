package com.campusguard.post;

import java.util.List;

/**
 * A slice of the feed, and whether there is more of it.
 *
 * <p>The feed used to return a bare array, which left a client unable to tell a
 * last page from a full one without asking again and getting nothing. It also had
 * no total count, and still does not: counting every live post in a forum to
 * render twenty of them is work nobody reads, and the answer is stale by the time
 * it arrives.
 *
 * @param hasMore established by fetching one row beyond the page and discarding
 *     it, so the question costs a row rather than a second query
 * @param nextCursor null on the last page. Passing it back returns the next slice
 *     from exactly where this one stopped, which offsets cannot promise once
 *     someone posts while a reader is paging.
 */
public record FeedPage(List<PostResponse> items, boolean hasMore, String nextCursor) {
}
