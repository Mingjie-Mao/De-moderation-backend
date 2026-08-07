package com.campusguard.post;

import com.campusguard.common.NotFoundException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Where the previous page of the feed stopped.
 *
 * <p>Carries the creation instant and the id together. The instant alone is not a
 * unique position: two posts created in the same microsecond would make the
 * boundary ambiguous, and a reader would either see one twice or miss it
 * entirely. The id breaks that tie, which is why the ordering names both.
 *
 * <p>Encoded opaquely so that clients treat it as a token to hand back rather than
 * as a timestamp to do arithmetic on. What is inside it is this service's
 * business and should stay changeable.
 */
public record FeedCursor(Instant createdAt, UUID id) {

    public String encode() {
        String raw = createdAt.toString() + '|' + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static FeedCursor decode(String encoded) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int separator = raw.lastIndexOf('|');
            return new FeedCursor(Instant.parse(raw.substring(0, separator)), UUID.fromString(raw.substring(separator + 1)));
        } catch (RuntimeException ex) {
            // A cursor is something this service issued. One that will not decode
            // was invented or corrupted, and silently returning the first page
            // would hide that from whoever is paging.
            throw new NotFoundException("That page cursor is not valid.");
        }
    }

    public static FeedCursor of(PostResponse post) {
        return new FeedCursor(post.createdAt(), post.id());
    }
}
