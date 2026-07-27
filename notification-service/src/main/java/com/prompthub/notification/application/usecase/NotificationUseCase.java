package com.prompthub.notification.application.usecase;

import com.prompthub.notification.application.command.CreateNotificationCommand;
import com.prompthub.notification.application.dto.NotificationReadResponse;
import com.prompthub.notification.application.dto.NotificationResponse;
import com.prompthub.notification.application.dto.ReadAllNotificationsResponse;
import com.prompthub.notification.application.dto.UnreadNotificationCountResponse;
import com.prompthub.notification.application.dto.NotificationReplayResult;
import com.prompthub.notification.domain.enums.NotificationCategory;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface NotificationUseCase {
    Page<NotificationResponse> getNotifications(UUID recipientId, NotificationCategory category, int page, int size);
    UnreadNotificationCountResponse getUnreadCount(UUID recipientId);
    NotificationReadResponse readNotification(UUID recipientId, UUID notificationId);
    ReadAllNotificationsResponse readAllNotifications(UUID recipientId);
    NotificationResponse createNotification(CreateNotificationCommand command);
    long deleteExpiredNotifications();
    NotificationReplayResult getReplay(UUID recipientId, UUID lastEventId);
}
