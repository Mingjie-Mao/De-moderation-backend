package com.campusguard.media;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;

/**
 * The same contract, against the bucket a deployment will actually use.
 *
 * <p>{@link S3MediaStorageTest} runs it against MinIO, which speaks the S3 API
 * and is the right thing for CI: it needs no account and no network. What it
 * cannot answer is whether the provider a deployment is pointed at agrees —
 * Cloudflare R2, AWS and GCS differ on region naming, on whether path-style
 * addressing is required, and R2 in particular returns no region at all. Those
 * are configuration mistakes that a MinIO run cannot catch and that surface as
 * a media upload failing in production.
 *
 * <p>Skipped unless {@code MEDIA_S3_BUCKET} is in the environment, like the
 * suites that call a real model. Run it after filling in the S3 block of
 * {@code .env}:
 *
 * <pre>
 * set -a &amp;&amp; . ./.env &amp;&amp; set +a
 * mvn -Dtest=RealBucketMediaStorageTest test
 * </pre>
 *
 * <p>Writes into a prefix of its own and deletes it afterwards. The prefix is
 * what makes this safe to point at a bucket that also holds real media: every
 * key this test can see or remove is one it wrote, which is the same guarantee
 * the orphan sweep relies on.
 */
@EnabledIfEnvironmentVariable(named = "MEDIA_S3_BUCKET", matches = ".+")
class RealBucketMediaStorageTest extends MediaStorageContract {

    /** One root for the run, so cleanup can remove everything under it in one call. */
    private static final String ROOT = "contract-test/" + UUID.randomUUID() + "/";

    private static S3Client client;
    private static String bucket;

    private MediaStorage storage;

    @BeforeAll
    static void connect() {
        bucket = System.getenv("MEDIA_S3_BUCKET");
        String endpoint = System.getenv("MEDIA_S3_ENDPOINT");
        String region = orDefault(System.getenv("MEDIA_S3_REGION"), "auto");
        boolean pathStyle = !"false".equalsIgnoreCase(orDefault(System.getenv("MEDIA_S3_PATH_STYLE"), "true"));

        S3ClientBuilder builder = S3Client.builder()
                .httpClient(UrlConnectionHttpClient.create())
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        System.getenv("MEDIA_S3_ACCESS_KEY"), System.getenv("MEDIA_S3_SECRET_KEY"))))
                .forcePathStyle(pathStyle);

        // Empty for AWS itself, which derives the endpoint from the region.
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }

        client = builder.build();
    }

    /**
     * A prefix per test, under the run's own root.
     *
     * <p>The contract asserts on what listing returns, so two tests sharing a
     * prefix would see each other's objects and the emptiness assertions would
     * fail against whatever ran first. {@code S3MediaStorageTest} namespaces for
     * the same reason; against a real bucket it also bounds what cleanup touches.
     */
    @BeforeEach
    void namespace() {
        storage = new S3MediaStorage(
                client, bucket, ROOT + UUID.randomUUID().toString().substring(0, 8) + "/");
    }

    /**
     * Removes what the run wrote, including the keys the contract deliberately
     * leaves behind. A failed assertion must not be the reason a bucket keeps
     * accumulating test objects, so this runs regardless of the outcome.
     */
    @AfterAll
    static void cleanUp() {
        if (client == null) {
            return;
        }
        List<ObjectIdentifier> keys = client.listObjectsV2(request -> request.bucket(bucket).prefix(ROOT))
                .contents().stream()
                .map(object -> ObjectIdentifier.builder().key(object.key()).build())
                .toList();

        if (!keys.isEmpty()) {
            client.deleteObjects(request -> request.bucket(bucket).delete(d -> d.objects(keys)));
        }
        client.close();
    }

    @Override
    protected MediaStorage storage() {
        return storage;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
