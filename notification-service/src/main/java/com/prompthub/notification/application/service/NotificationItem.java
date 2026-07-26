package com.prompthub.notification.application.service;

import java.time.Instant;
import java.util.UUID;

public record NotificationItem(UUID id, long sequence, String title, String message, boolean read, Instant createdAt) {
}
