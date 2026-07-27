package com.prompthub.notification.application.event;

import java.util.UUID;

public record NotificationCreatedEvent(UUID notificationId, UUID recipientId) {
}
