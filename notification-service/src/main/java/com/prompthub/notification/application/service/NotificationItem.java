package com.prompthub.notification.application.service;

import com.prompthub.notification.domain.enums.NotificationType;
import java.time.Instant;
import java.util.UUID;

public record NotificationItem(
    UUID id,
    long sequence,
    NotificationType type,
    String title,
    String message,
    String referenceType,
    UUID referenceId,
    boolean read,
    Instant createdAt
) {
}
