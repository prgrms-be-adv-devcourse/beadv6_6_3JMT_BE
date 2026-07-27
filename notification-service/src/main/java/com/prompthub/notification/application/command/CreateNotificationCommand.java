package com.prompthub.notification.application.command;

import com.prompthub.notification.domain.enums.NotificationCategory;
import com.prompthub.notification.domain.enums.NotificationType;

import java.time.Instant;
import java.util.UUID;

public record CreateNotificationCommand(
    UUID recipientId, NotificationType type, NotificationCategory category, String title, String content,
    String linkUrl, String referenceType, UUID referenceId, Instant occurredAt, String deduplicationKey
) {
}
