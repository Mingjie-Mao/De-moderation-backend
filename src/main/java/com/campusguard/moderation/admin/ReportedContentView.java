package com.campusguard.moderation.admin;

import com.campusguard.moderation.ContentLocator;
import java.util.UUID;

public record ReportedContentView(String title, String body, UUID authorId) {

    public static ReportedContentView of(ContentLocator.ModeratedContent content) {
        return new ReportedContentView(content.title(), content.body(), content.authorId());
    }
}
