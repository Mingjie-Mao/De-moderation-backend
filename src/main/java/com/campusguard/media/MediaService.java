package com.campusguard.media;

import com.campusguard.common.NotFoundException;
import com.campusguard.user.User;
import com.campusguard.user.UserRepository;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MediaService {
    private final MediaObjectRepository media;
    private final UserRepository users;
    private final MediaStorage storage;
    private final MediaProperties properties;

    public MediaService(
            MediaObjectRepository media,
            UserRepository users,
            MediaStorage storage,
            MediaProperties properties) {
        this.media = media; this.users = users; this.storage = storage; this.properties = properties;
    }

    @Transactional
    public MediaResponse upload(UUID ownerId, MultipartFile file) {
        if (file == null || file.isEmpty()) throw bad("Choose a non-empty image.");
        if (file.getSize() > properties.maxUploadSize().toBytes()) throw bad("The image is too large.");
        User owner = users.findById(ownerId).orElseThrow(() -> new NotFoundException("No user with id " + ownerId));

        byte[] normalized;
        String type;
        String extension;
        try {
            byte[] source = file.getBytes();
            BufferedImage image;
            String format;
            try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(source))) {
                Iterator<ImageReader> readers = input == null
                        ? java.util.Collections.emptyIterator()
                        : ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw bad("Only valid JPEG and PNG images are accepted.");
                ImageReader reader = readers.next();
                try {
                    reader.setInput(input, true, true);
                    format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                    if (!(format.equals("jpeg") || format.equals("jpg") || format.equals("png")))
                        throw bad("Only valid JPEG and PNG images are accepted.");
                    long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                    if (pixels > properties.maxPixels()) throw bad("The image dimensions are too large.");
                    image = reader.read(0);
                } finally {
                    reader.dispose();
                }
            }
            boolean jpeg = format.equals("jpeg") || format.equals("jpg");
            type = jpeg ? "image/jpeg" : "image/png";
            extension = jpeg ? ".jpg" : ".png";
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, jpeg ? "jpg" : "png", output)) throw bad("The image could not be normalized.");
            normalized = output.toByteArray();
            if (normalized.length > properties.maxUploadSize().toBytes())
                throw bad("The normalized image is too large.");
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw bad("The uploaded file is not a readable image.");
        }

        String key = UUID.randomUUID() + extension;
        try {
            storage.put(key, normalized, type);
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalized));
            MediaObject saved = media.saveAndFlush(new MediaObject(owner, type, normalized.length, digest, key));
            return MediaResponse.of(saved);
        } catch (Exception ex) {
            // Compensation, because the bytes and the row cannot be written in
            // one transaction. It covers the ordinary failure and not a process
            // that dies between the two, which is what MediaSweep is for.
            try { storage.delete(key); } catch (Exception ignored) {}
            throw new IllegalStateException("The image could not be stored.", ex);
        }
    }

    @Transactional(readOnly = true)
    public StoredMedia read(UUID id) {
        MediaObject object = require(id);
        if (media.countVisiblePostReferences(id) == 0
                && media.countVisibleCommentReferences(id) == 0) {
            throw new NotFoundException("Media " + id + " is not attached to visible content.");
        }
        // A row whose bytes have gone reads as a missing image rather than as a
        // server fault. That is the state an ephemeral container filesystem left
        // behind on every release, and a 500 for each of those images would have
        // turned lost pictures into broken pages.
        return storage.get(object.getStorageKey())
                .map(bytes -> new StoredMedia(object, bytes))
                .orElseThrow(() -> new NotFoundException("Media " + id + " is not available."));
    }

    @Transactional(readOnly = true)
    public MediaObject requireOwned(UUID id, UUID ownerId) {
        MediaObject object = require(id);
        if (!object.getOwner().getId().equals(ownerId)) throw new NotFoundException("No media with id " + id);
        return object;
    }

    private MediaObject require(UUID id) {
        return media.findById(id).orElseThrow(() -> new NotFoundException("No media with id " + id));
    }
    private ResponseStatusException bad(String detail) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail); }
    public record StoredMedia(MediaObject metadata, byte[] bytes) {}
}
