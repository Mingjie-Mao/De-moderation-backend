package com.campusguard.notification;

import java.time.Instant;
import java.util.UUID;

public record NotificationView(UUID id, String type, String title, String body, String referenceType,
                               UUID referenceId, Instant readAt, Instant createdAt) {
    static NotificationView of(Notification n) {
        return new NotificationView(n.getId(), n.getType(), n.getTitle(), n.getBody(), n.getReferenceType(),
                n.getReferenceId(), n.getReadAt(), n.getCreatedAt());
    }
}
