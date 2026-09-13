package com.campusguard.media;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Where the bytes of an image live, as the rest of the application needs to see
 * it.
 *
 * <p>Extracted because the answer was wrong in production and the wrongness was
 * invisible. Media was written to a directory inside the container, which on the
 * deployment platform is not preserved across a rebuild: every release silently
 * dropped every uploaded image while the database kept every row describing them.
 * Nothing failed, nothing was logged, and the only symptom was that old posts
 * lost their pictures.
 *
 * <p>Four methods, chosen by what the application actually does with storage
 * rather than by what a provider offers. There is no move, no copy, no signed
 * URL and no metadata: a fifth method would be a fifth thing every future backend
 * has to implement correctly.
 *
 * <p>{@link #list} exists for the orphan sweep and nothing else, which is why it
 * pages. A bucket after a year holds more keys than a sweep should hold in
 * memory, and a method that returned all of them would work in every test and
 * fail exactly once.
 */
public interface MediaStorage {

    /**
     * Stores bytes under a key that must not already exist.
     *
     * <p>Refusing to overwrite is the point. Keys are generated per upload, so a
     * collision is not a retry — it is two different images with one name, and
     * the quiet version of that bug is one user's post showing another user's
     * photo.
     *
     * @throws MediaStorageException if the key is taken or the write fails
     */
    void put(String key, byte[] bytes, String contentType);

    /**
     * Empty when no object has that key, which is a normal answer rather than an
     * error. A row whose object has gone — the exact damage the ephemeral
     * directory did — has to read as a missing image and not as a failure of the
     * request.
     */
    Optional<byte[]> get(String key);

    /**
     * Whether an object is there, without fetching it.
     *
     * <p>Separate from {@link #get} because the sweep asks this question about
     * every row it checks and needs none of the bytes. Answering it with a get
     * would download the whole store once per pass — on the filesystem that is
     * disk and heap, on S3 it is egress billed by the gigabyte to establish
     * something a HEAD request answers in a few hundred bytes.
     */
    boolean exists(String key);

    /** True when this call removed something, so a sweep can report what it did rather than what it attempted. */
    boolean delete(String key);

    /**
     * One page of stored objects in lexicographic order by key.
     *
     * @param after the last key of the previous page, or null to start
     * @return at most {@code limit} objects; fewer means the end
     */
    List<StoredObject> list(String after, int limit);

    /** Named in logs and on startup, so which backend is live is never a guess. */
    String describe();

    /**
     * @param modifiedAt when the object was last written, which the sweep needs
     *     and a key alone cannot give. Without it there is no way to tell an
     *     object whose database row was never committed from one being uploaded
     *     right now, and a sweep that cannot tell those apart deletes a user's
     *     image mid-request.
     */
    record StoredObject(String key, Instant modifiedAt) {
    }
}
