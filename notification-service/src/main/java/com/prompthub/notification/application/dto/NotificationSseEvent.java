package com.prompthub.notification.application.dto;
import java.util.UUID;
public record NotificationSseEvent(UUID notificationId, NotificationSsePayload payload) {}
