package com.campusguard.media;

import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

/**
 * Chooses the storage backend once, at startup, and says which one it chose.
 *
 * <p>A single bean rather than a runtime branch inside {@link MediaService}. The
 * service should not contain the word S3 anywhere: which backend is live is a
 * deployment decision, and the moment a conditional about it appears in the
 * upload path, every future change has to be reasoned about twice.
 */
@Configuration
@EnableConfigurationProperties(MediaProperties.class)
public class MediaConfig {

    private static final Logger log = LoggerFactory.getLogger(MediaConfig.class);

    @Bean
    public MediaStorage mediaStorage(MediaProperties properties) {
        MediaStorage storage = build(properties);

        // Logged at startup because the failure this replaced was silent. An
        // operator who can see "filesystem:/data/media" in the first hundred
        // lines of a deployment's log has a chance of noticing that the platform
        // does not keep that directory.
        log.info("Media storage is {}", storage.describe());

        if (properties.backend() == MediaProperties.Backend.FILESYSTEM) {
            log.info("Filesystem media storage is durable only if {} is a mounted volume that survives a rebuild.",
                    properties.storagePath());
        }

        return storage;
    }

    private MediaStorage build(MediaProperties properties) {
        return switch (properties.backend()) {
            case FILESYSTEM -> new FilesystemMediaStorage(properties.storagePath());
            case S3 -> new S3MediaStorage(s3Client(properties.s3()), properties.s3().bucket(),
                    properties.s3().prefix());
        };
    }

    /**
     * Built here rather than exposed as its own bean, so nothing else in the
     * application can reach a cloud client it has no business holding.
     *
     * <p>Credentials are static and explicit. The SDK's default provider chain
     * would also read environment variables, profile files and instance metadata,
     * which on a developer's laptop means an accidentally configured personal AWS
     * account can end up serving a student forum's images.
     */
    private S3Client s3Client(MediaProperties.S3 s3) {
        S3ClientBuilder builder = S3Client.builder()
                .httpClient(UrlConnectionHttpClient.create())
                .region(Region.of(s3.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(s3.accessKey(), s3.secretKey())))
                .forcePathStyle(s3.pathStyle());

        if (s3.endpoint() != null && !s3.endpoint().isBlank()) {
            builder.endpointOverride(URI.create(s3.endpoint()));
        }

        return builder.build();
    }
}
