package com.campusguard.media;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties("campusguard.media")
public record MediaProperties(Path storagePath, DataSize maxUploadSize, long maxPixels) {
    public MediaProperties {
        if (storagePath == null) throw new IllegalStateException("Media storage path is required.");
        if (maxUploadSize == null || maxUploadSize.toBytes() <= 0)
            throw new IllegalStateException("Media max upload size must be positive.");
        if (maxPixels < 1) throw new IllegalStateException("Media max pixels must be positive.");
    }
}
