package com.campusguard.media;

import java.util.List;
import java.util.Optional;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * A bucket, on anything that speaks the S3 API: AWS, Cloudflare R2, or GCS in
 * its interoperability mode.
 *
 * <p>One implementation for three providers, which is the reason this project
 * uses the S3 protocol rather than a vendor's own SDK. Choosing between them is
 * then an endpoint and a pair of credentials, and none of the code above this
 * class learns which one is live.
 *
 * <p>Objects survive a container rebuild, which is the whole point: the
 * filesystem backend was losing every image on every release, and no amount of
 * care in this application could have prevented it.
 */
public class S3MediaStorage implements MediaStorage {

    private final S3Client client;
    private final String bucket;
    private final String prefix;

    /**
     * @param prefix folder to keep media under, so a bucket shared with backups
     *     or exports has a boundary the sweep respects. Normalised to end in a
     *     slash, or to be empty.
     */
    public S3MediaStorage(S3Client client, String bucket, String prefix) {
        this.client = client;
        this.bucket = bucket;
        this.prefix = prefix == null || prefix.isBlank()
                ? ""
                : prefix.endsWith("/") ? prefix : prefix + "/";
    }

    /**
     * S3 has no create-if-absent, so the check and the write are two calls and
     * cannot be made atomic against a concurrent writer.
     *
     * <p>Acceptable here, and worth being explicit about why: keys are random
     * UUIDs minted per upload, so two writers racing on one key would mean a UUID
     * collision rather than a lost update. The head request is there to catch a
     * key being reused by a caller, which is a programming error, not to
     * synchronise anything.
     */
    @Override
    public void put(String key, byte[] bytes, String contentType) {
        if (exists(key)) {
            throw new MediaStorageException("Media key " + key + " is already taken.");
        }

        String objectKey = objectKey(key);
        try {
            client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(objectKey)
                            .contentType(contentType)
                            .contentLength((long) bytes.length)
                            .build(),
                    RequestBody.fromBytes(bytes));
        } catch (SdkException ex) {
            throw new MediaStorageException("Could not write media key " + key + ".", ex);
        }
    }

    @Override
    public Optional<byte[]> get(String key) {
        try {
            return Optional.of(client.getObjectAsBytes(request -> request.bucket(bucket).key(objectKey(key)))
                    .asByteArray());
        } catch (NoSuchKeyException ex) {
            return Optional.empty();
        } catch (SdkException ex) {
            throw new MediaStorageException("Could not read media key " + key + ".", ex);
        }
    }

    @Override
    public boolean exists(String key) {
        String objectKey = objectKey(key);
        try {
            client.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey).build());
            return true;
        } catch (NoSuchKeyException ex) {
            return false;
        } catch (SdkException ex) {
            throw new MediaStorageException("Could not check media key " + key + ".", ex);
        }
    }

    /**
     * S3 answers a delete of a key that was never there with success, so the
     * existence check is what makes the return value mean what the interface says
     * it means. A sweep that reported deletions it did not make would be a sweep
     * whose logs cannot be used to check it.
     */
    @Override
    public boolean delete(String key) {
        if (!exists(key)) {
            return false;
        }

        try {
            client.deleteObject(request -> request.bucket(bucket).key(objectKey(key)));
            return true;
        } catch (SdkException ex) {
            throw new MediaStorageException("Could not delete media key " + key + ".", ex);
        }
    }

    /**
     * {@code startAfter} rather than a continuation token, so a page can be asked
     * for from nothing but the previous page's last key. The sweep holds no
     * session, may run in a different process than the one that read the previous
     * page, and S3 returns keys in lexicographic order, which makes the last key
     * a complete cursor.
     */
    @Override
    public List<StoredObject> list(String after, int limit) {
        ListObjectsV2Request.Builder request = ListObjectsV2Request.builder()
                .bucket(bucket)
                .maxKeys(limit);
        if (!prefix.isEmpty()) {
            request.prefix(prefix);
        }
        if (after != null) {
            request.startAfter(objectKey(after));
        }

        try {
            ListObjectsV2Response response = client.listObjectsV2(request.build());
            return response.contents().stream()
                    .filter(object -> !mediaKey(object.key()).isEmpty())
                    .map(object -> new StoredObject(mediaKey(object.key()), object.lastModified()))
                    .toList();
        } catch (SdkException ex) {
            throw new MediaStorageException("Could not list media in bucket " + bucket + ".", ex);
        }
    }

    @Override
    public String describe() {
        return "s3:" + bucket + "/" + prefix;
    }

    private String objectKey(String key) {
        if (key == null || key.isBlank()) {
            throw new MediaStorageException("A media key is required.");
        }
        // The stored key is opaque to everything above this class, so a key
        // carrying a slash would silently create a folder and then not be found
        // by a sweep that strips only one prefix.
        if (key.contains("/")) {
            throw new MediaStorageException("Media key " + key + " must not contain a path separator.");
        }
        return prefix + key;
    }

    private String mediaKey(String objectKey) {
        return objectKey.startsWith(prefix) ? objectKey.substring(prefix.length()) : objectKey;
    }
}
