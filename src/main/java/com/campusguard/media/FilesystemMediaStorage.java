package com.campusguard.media;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A directory on the machine running the process.
 *
 * <p>Correct for local development and for a deployment with a real volume
 * mounted, and wrong for a container filesystem that is rebuilt on release —
 * which is how it was being used. It stays as the default because it needs no
 * credentials and because a developer running the project for the first time
 * should not have to hold an account with a cloud provider.
 *
 * <p>The containment check on every key is not about the keys this application
 * generates, which are UUIDs. It is about the keys it reads: those come back from
 * the database, and a storage layer that resolves whatever it is handed relative
 * to a root is one bad row away from serving {@code ../../etc/passwd}.
 */
public class FilesystemMediaStorage implements MediaStorage {

    private final Path root;

    public FilesystemMediaStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        Path destination = resolve(key);
        try {
            Files.createDirectories(root);
            // CREATE_NEW rather than CREATE: the failure of a collision is worth
            // having, and an overwrite would be silent.
            Files.write(destination, bytes, StandardOpenOption.CREATE_NEW);
        } catch (java.nio.file.FileAlreadyExistsException ex) {
            throw new MediaStorageException("Media key " + key + " is already taken.", ex);
        } catch (IOException ex) {
            throw new MediaStorageException("Could not write media key " + key + ".", ex);
        }
    }

    @Override
    public Optional<byte[]> get(String key) {
        try {
            return Optional.of(Files.readAllBytes(resolve(key)));
        } catch (NoSuchFileException ex) {
            return Optional.empty();
        } catch (IOException ex) {
            throw new MediaStorageException("Could not read media key " + key + ".", ex);
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.isRegularFile(resolve(key));
    }

    @Override
    public boolean delete(String key) {
        try {
            return Files.deleteIfExists(resolve(key));
        } catch (IOException ex) {
            throw new MediaStorageException("Could not delete media key " + key + ".", ex);
        }
    }

    /**
     * Sorted and windowed in this method rather than left to the filesystem.
     * Directory iteration order is unspecified, and a sweep that pages through an
     * unspecified order can see a key twice and never see another.
     */
    @Override
    public List<StoredObject> list(String after, int limit) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(root)) {
            return entries.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> after == null || name.compareTo(after) > 0)
                    .sorted(Comparator.naturalOrder())
                    .limit(limit)
                    .map(name -> new StoredObject(name, modifiedAt(name)))
                    .toList();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * The epoch when the file has gone between being listed and being stat'd.
     * Treating a vanished file as ancient is harmless — the sweep's next step is
     * to delete it, and deleting what is not there is a no-op — whereas throwing
     * would end a sweep over one file somebody else removed.
     */
    private Instant modifiedAt(String key) {
        try {
            return Files.getLastModifiedTime(resolve(key)).toInstant();
        } catch (IOException ex) {
            return Instant.EPOCH;
        }
    }

    @Override
    public String describe() {
        return "filesystem:" + root;
    }

    private Path resolve(String key) {
        if (key == null || key.isBlank()) {
            throw new MediaStorageException("A media key is required.");
        }
        Path destination = root.resolve(key).normalize();
        if (!destination.startsWith(root) || destination.equals(root)) {
            throw new MediaStorageException("Media key " + key + " escapes the storage root.");
        }
        return destination;
    }
}
