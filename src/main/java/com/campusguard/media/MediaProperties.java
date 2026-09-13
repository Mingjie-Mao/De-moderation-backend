package com.campusguard.media;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * @param backend which {@link MediaStorage} is live. Filesystem by default so the
 *     project still starts with no credentials; anything deployed on a container
 *     filesystem that is rebuilt on release must set {@code s3}, because there the
 *     default loses every image on every deployment.
 * @param storagePath required by, and only meaningful to, the filesystem backend
 * @param s3 required by, and only meaningful to, the S3 backend
 */
@ConfigurationProperties("campusguard.media")
public record MediaProperties(
        @DefaultValue("FILESYSTEM") Backend backend,
        Path storagePath,
        @DefaultValue S3 s3,
        DataSize maxUploadSize,
        long maxPixels,
        @DefaultValue Sweep sweep) {

    public MediaProperties {
        if (maxUploadSize == null || maxUploadSize.toBytes() <= 0) {
            throw new IllegalStateException("Media max upload size must be positive.");
        }
        if (maxPixels < 1) {
            throw new IllegalStateException("Media max pixels must be positive.");
        }
        // Checked per backend rather than unconditionally. Demanding a storage
        // path from an S3 deployment would mean carrying a directory that is
        // never read, and the next person to maintain it could not tell whether
        // it mattered.
        if (backend == Backend.FILESYSTEM && storagePath == null) {
            throw new IllegalStateException("campusguard.media.storage-path is required by the filesystem backend.");
        }
        if (backend == Backend.S3) {
            s3.validate();
        }
    }

    public enum Backend {

        /** A directory on the machine. Durable only if something durable is mounted there. */
        FILESYSTEM,

        /** Any bucket speaking the S3 API: AWS S3, Cloudflare R2, or GCS in interoperability mode. */
        S3
    }

    /**
     * @param endpoint empty for AWS itself, set for R2 or GCS. Their endpoints are
     *     what make one implementation cover three providers.
     * @param pathStyle addressing as {@code endpoint/bucket/key} rather than
     *     {@code bucket.endpoint/key}. Required by MinIO and by most
     *     S3-compatible servers that are not AWS.
     * @param prefix a folder inside the bucket. Worth setting whenever the bucket
     *     holds anything else, because the orphan sweep deletes stored objects it
     *     cannot find a database row for, and without a prefix "anything else" is
     *     inside its reach.
     */
    public record S3(
            String bucket,
            @DefaultValue("auto") String region,
            String endpoint,
            String accessKey,
            String secretKey,
            @DefaultValue("true") boolean pathStyle,
            @DefaultValue("media/") String prefix) {

        void validate() {
            if (bucket == null || bucket.isBlank()) {
                throw new IllegalStateException("campusguard.media.s3.bucket is required by the S3 backend.");
            }
            if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()) {
                throw new IllegalStateException(
                        "campusguard.media.s3.access-key and secret-key are required by the S3 backend.");
            }
        }
    }

    /**
     * The sweep that deletes what nothing refers to.
     *
     * @param enabled off by default. It deletes, and a deleting job should be
     *     turned on by someone who has read what it deletes.
     * @param grace how old an orphan must be before it is removed. This is the
     *     only thing standing between the sweep and an upload that is still in
     *     flight: between writing the bytes and committing the row there is a
     *     moment where a stored object legitimately has no database row, and a
     *     sweep with no grace period would delete exactly that.
     * @param batchSize how many of each kind of orphan one pass handles, so a
     *     bucket with a year of debris in it is cleared over several passes
     *     instead of in one transaction holding thousands of rows.
     */
    public record Sweep(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("24h") Duration grace,
            @DefaultValue("200") int batchSize,
            @DefaultValue("1h") Duration interval) {

        public Sweep {
            if (grace.isNegative()) {
                throw new IllegalStateException("campusguard.media.sweep.grace must not be negative.");
            }
            if (batchSize < 1) {
                throw new IllegalStateException("campusguard.media.sweep.batch-size must be at least 1.");
            }
        }
    }
}
