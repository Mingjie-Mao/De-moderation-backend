package com.campusguard.media;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * Deletes media nothing refers to, and reports media whose bytes have gone.
 *
 * <p>Storage leaks in two directions and they need opposite treatment.
 *
 * <p><b>An object with no row</b> is unreachable and costs money for as long as
 * the bucket exists. Nothing in the application will ever ask for it, because the
 * only path to a stored key is through a database row. It is safe to delete and
 * impossible to notice, which is the worst combination: a bucket can accumulate
 * these for a year and the only symptom is the bill.
 *
 * <p><b>A row with no object</b> is the opposite: harmless to storage, visible to
 * users as a missing image, and <em>never deleted here</em>. The row may still be
 * referenced by a live post, and removing it would turn a missing picture into a
 * broken page. It is counted and logged instead, because a rising count is the
 * signature of storage that does not survive deployment — the failure this whole
 * seam was built to end.
 *
 * <p>Every deletion waits out a grace period. Between writing bytes and
 * committing a row there is a legitimate moment when a stored object has no row,
 * and a sweep that did not wait would delete an image out from under the request
 * uploading it.
 */
@Service
public class MediaSweep {

    private static final Logger log = LoggerFactory.getLogger(MediaSweep.class);

    private final MediaObjectRepository media;
    private final MediaStorage storage;
    private final MediaProperties properties;

    public MediaSweep(MediaObjectRepository media, MediaStorage storage, MediaProperties properties) {
        this.media = media;
        this.storage = storage;
        this.properties = properties;
    }

    public Result runOnce() {
        Instant cutoff = Instant.now().minus(properties.sweep().grace());
        int batch = properties.sweep().batchSize();

        Result result = new Result(
                deleteUnreferencedRows(cutoff, batch),
                deleteUnknownObjects(cutoff, batch),
                countRowsWithoutBytes(batch));

        if (result.isEmpty()) {
            log.debug("Media sweep found nothing to do.");
        } else {
            log.info("Media sweep: {}", result);
        }
        if (result.rowsMissingBytes() > 0) {
            log.warn(
                    "{} media row(s) have no object in {}. Rows are never deleted for this reason; a number that "
                            + "keeps rising means storage is not surviving deployment.",
                    result.rowsMissingBytes(), storage.describe());
        }

        return result;
    }

    /**
     * The row goes first and the bytes second, never the other way round.
     *
     * <p>They cannot be removed atomically, so one order has to be chosen and
     * lived with. Bytes-first would leave, on a crash in between, a row pointing
     * at nothing — a post rendering a missing image, which is a user-visible
     * fault. Row-first leaves an object nobody refers to, which is invisible and
     * which the next pass of this same sweep collects. One of those is a bug and
     * the other is a retry.
     */
    private int deleteUnreferencedRows(Instant cutoff, int batch) {
        List<MediaObject> orphans = media.findUnreferencedBefore(cutoff, PageRequest.of(0, batch));

        int deleted = 0;
        for (MediaObject orphan : orphans) {
            String key = orphan.getStorageKey();
            media.delete(orphan);
            try {
                storage.delete(key);
            } catch (MediaStorageException ex) {
                // The row is gone, so the object is now an orphan of the other
                // kind and the next pass will find it. Worth a line, not a
                // failure.
                log.warn("Deleted media row for key {} but could not delete the object: {}", key, ex.getMessage());
            }
            deleted++;
        }
        return deleted;
    }

    /**
     * Walks stored keys and removes the ones no row claims.
     *
     * <p>Paged through storage rather than compared as two whole sets. The
     * database side of that comparison is a column this service can hold in
     * memory; the storage side, after a year, is not.
     */
    private int deleteUnknownObjects(Instant cutoff, int batch) {
        List<String> unknown = new ArrayList<>();
        String after = null;

        while (unknown.size() < batch) {
            List<MediaStorage.StoredObject> page = storage.list(after, Math.min(batch, 500));
            if (page.isEmpty()) {
                break;
            }
            after = page.getLast().key();

            List<String> candidates = page.stream()
                    .filter(object -> object.modifiedAt().isBefore(cutoff))
                    .map(MediaStorage.StoredObject::key)
                    .toList();

            if (!candidates.isEmpty()) {
                Set<String> known = new LinkedHashSet<>(media.findKnownStorageKeys(candidates));
                candidates.stream()
                        .filter(key -> !known.contains(key))
                        .limit(batch - unknown.size())
                        .forEach(unknown::add);
            }
        }

        unknown.forEach(storage::delete);
        return unknown.size();
    }

    /**
     * Counted, never deleted. See the class comment: this is the number that says
     * whether storage is durable, and the rows behind it may still be referenced
     * by live content.
     */
    private int countRowsWithoutBytes(int batch) {
        // Newest first, and ordered explicitly: an unsorted page is whatever the
        // database felt like returning, and the rows worth checking are the
        // recent ones, because a deployment that loses storage loses the images
        // uploaded since the last one.
        PageRequest page = PageRequest.of(0, batch, Sort.by(Sort.Direction.DESC, "createdAt"));
        return (int) media.findAll(page).getContent().stream()
                .filter(object -> !storage.exists(object.getStorageKey()))
                .count();
    }

    /**
     * @param rowsMissingBytes rows whose object is gone. Reported rather than
     *     acted on, and the one figure here that should always be zero.
     */
    public record Result(int rowsDeleted, int objectsDeleted, int rowsMissingBytes) {

        public boolean isEmpty() {
            return rowsDeleted == 0 && objectsDeleted == 0 && rowsMissingBytes == 0;
        }

        @Override
        public String toString() {
            return "%d unreferenced row(s) deleted, %d unknown object(s) deleted, %d row(s) with no object"
                    .formatted(rowsDeleted, objectsDeleted, rowsMissingBytes);
        }
    }
}
