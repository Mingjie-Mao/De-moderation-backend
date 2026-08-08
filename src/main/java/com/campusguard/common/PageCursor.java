package com.campusguard.common;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Where a page stopped: a creation instant and the id that breaks its ties.
 *
 * <p>The instant alone is not a unique position. Two rows created in the same
 * microsecond leave the boundary ambiguous, and a reader paging across it sees
 * one of them twice or neither, which is exactly the bug keyset paging exists to
 * avoid. The id settles it, which is why every ordering that uses this names
 * both columns.
 *
 * <p>Encoded opaquely so clients hand it back rather than doing arithmetic on it.
 * What is inside stays this service's business, and therefore stays changeable.
 *
 * <p>Shared by the feed and by comment threads. The two order in opposite
 * directions — a feed is newest first, a thread reads oldest first — but a
 * position is a position, and two copies of a base64 codec is two things to keep
 * in step.
 */
public record PageCursor(Instant createdAt, UUID id) {

    public String encode() {
        String raw = createdAt.toString() + '|' + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static PageCursor decode(String encoded) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int separator = raw.lastIndexOf('|');
            return new PageCursor(
                    Instant.parse(raw.substring(0, separator)), UUID.fromString(raw.substring(separator + 1)));
        } catch (RuntimeException ex) {
            // A cursor is something this service issued. One that will not decode
            // was invented or corrupted, and silently returning the first page
            // would hide that from whoever is paging.
            throw new NotFoundException("That page cursor is not valid.");
        }
    }
}
