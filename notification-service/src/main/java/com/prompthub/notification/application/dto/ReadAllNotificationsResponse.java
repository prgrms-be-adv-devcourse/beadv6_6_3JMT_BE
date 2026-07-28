package com.prompthub.notification.application.dto;

import java.time.Instant;

public record ReadAllNotificationsResponse(long updatedCount, Instant readAt) {
}
