package com.prompthub.notification.application.dto;
import java.util.UUID;
public record NotificationSsePayload(UUID notificationId, String type, String title, String content, long unreadCount) {}
