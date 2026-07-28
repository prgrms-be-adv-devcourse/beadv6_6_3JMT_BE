package com.prompthub.notification.application.dto;

import java.time.Instant;
import java.util.UUID;

public record NotificationReadResponse(UUID notificationId, boolean read, Instant readAt) {
}
