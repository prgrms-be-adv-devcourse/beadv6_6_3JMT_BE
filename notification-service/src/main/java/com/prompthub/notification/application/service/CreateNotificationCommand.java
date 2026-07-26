package com.prompthub.notification.application.service;

import com.prompthub.notification.domain.enums.NotificationType;
import java.time.Instant;
import java.util.UUID;

public record CreateNotificationCommand(UUID eventId, UUID recipientId, NotificationType type, String title,
                                        String message, String referenceType, UUID referenceId,
                                        String consumerGroup, Instant occurredAt) {
}
