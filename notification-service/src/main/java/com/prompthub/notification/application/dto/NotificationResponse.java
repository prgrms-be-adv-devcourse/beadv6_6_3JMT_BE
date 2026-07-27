package com.prompthub.notification.application.dto;

import com.prompthub.notification.domain.model.Notification;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
    UUID notificationId, String type, String category, String title, String content, String linkUrl,
    ReferenceResponse reference, boolean read, Instant readAt, Instant occurredAt, Instant createdAt
) {
    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(notification.getId(), notification.getType().name(), notification.getCategory().name(),
            notification.getTitle(), notification.getContent(), notification.getLinkUrl(),
            new ReferenceResponse(notification.getReferenceType(), notification.getReferenceId()), notification.isRead(),
            notification.getReadAt(), notification.getOccurredAt(), notification.getCreatedAt());
    }

    public record ReferenceResponse(String type, UUID id) {
    }
}
