package com.prompthub.notification.infra.realtime;

import com.prompthub.notification.application.dto.NotificationSsePayload;
import java.util.UUID;

public record NotificationRealtimeMessage(UUID recipientId, UUID notificationId, NotificationSsePayload payload) {}
