package com.campusguard.moderation.engine;

import com.campusguard.common.TargetType;
import java.util.UUID;
import java.util.List;

/**
 * The content to judge, plus the little context an engine is allowed to see.
 *
 * @param authorId present so a later engine can look up an author's history;
 *     the keyword engine ignores it
 */
public record ModerationRequest(
        TargetType targetType,
        UUID targetId,
        String title,
        String body,
        UUID authorId,
        UUID caseId,
        List<MediaInput> media) {

    public ModerationRequest {
        media = media == null ? List.of() : List.copyOf(media);
    }

    /**
     * Null {@code caseId} means this is not a case being worked, which is how the
     * evaluation harness calls an engine: there is no case to bill a model call
     * against, and nothing should be written to the invocation log for a sample
     * from a spreadsheet.
     */
    public static ModerationRequest of(TargetType targetType, UUID targetId, String title, String body, UUID authorId) {
        return new ModerationRequest(targetType, targetId, title, body, authorId, null, List.of());
    }

    public ModerationRequest forCase(UUID caseId) {
        return new ModerationRequest(targetType, targetId, title, body, authorId, caseId, media);
    }

    public ModerationRequest withMedia(MediaInput attachment) {
        return new ModerationRequest(targetType, targetId, title, body, authorId, caseId, List.of(attachment));
    }

    /** Title and body judged together: a clean post with an abusive title is still abusive. */
    public String fullText() {
        return title == null || title.isBlank() ? body : title + "\n" + body;
    }

    public record MediaInput(String contentType, byte[] bytes, String sha256) {
        public MediaInput { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
}
