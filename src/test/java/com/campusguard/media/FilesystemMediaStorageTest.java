package com.campusguard.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The default backend, plus the one hazard that is specific to having a
 * filesystem underneath.
 */
class FilesystemMediaStorageTest extends MediaStorageContract {

    @TempDir
    Path root;

    private MediaStorage storage;

    @BeforeEach
    void setUp() {
        storage = new FilesystemMediaStorage(root);
    }

    @Override
    protected MediaStorage storage() {
        return storage;
    }

    /**
     * Keys minted by this application are UUIDs, so this is not about them. It is
     * about keys that come back out of the database: one bad row, and a backend
     * that resolves whatever it is handed relative to a root will read
     * {@code ../../etc/passwd} and serve it over an endpoint that needs no
     * authentication.
     */
    @Test
    void refusesAKeyThatClimbsOutOfTheRoot() {
        assertThatThrownBy(() -> storage.get("../secret"))
                .isInstanceOf(MediaStorageException.class)
                .hasMessageContaining("escapes");
        assertThatThrownBy(() -> storage.put("../secret", "x".getBytes(StandardCharsets.UTF_8), "image/png"))
                .isInstanceOf(MediaStorageException.class);
        assertThatThrownBy(() -> storage.delete("nested/../../secret"))
                .isInstanceOf(MediaStorageException.class);
    }

    @Test
    void createsItsRootOnFirstWrite() throws IOException {
        Path missing = root.resolve("not-there-yet");
        MediaStorage fresh = new FilesystemMediaStorage(missing);

        fresh.put("k", "x".getBytes(StandardCharsets.UTF_8), "image/png");

        assertThat(Files.isDirectory(missing)).isTrue();
    }

    /** A directory that has never been written to is empty, not an error. */
    @Test
    void listsNothingWhenTheRootDoesNotExist() {
        assertThat(new FilesystemMediaStorage(root.resolve("absent")).list(null, 10)).isEmpty();
    }

    /** Subdirectories are not media. Listing one as a key would have the sweep try to delete it. */
    @Test
    void ignoresDirectoriesWhenListing() throws IOException {
        Files.createDirectory(root.resolve("a-folder"));
        storage.put("a-file", "x".getBytes(StandardCharsets.UTF_8), "image/png");

        assertThat(storage.list(null, 10)).singleElement()
                .satisfies(object -> assertThat(object.key()).isEqualTo("a-file"));
    }
}
