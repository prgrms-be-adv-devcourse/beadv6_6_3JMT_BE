package com.prompthub.notification.application.service;

import com.prompthub.notification.domain.model.Notification;
import com.prompthub.notification.infra.persistence.NotificationJpaRepository;
import com.prompthub.notification.global.exception.NotificationErrorCode;
import com.prompthub.notification.global.exception.NotificationException;
import lombok.RequiredArgsConstructor;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;

@Service
@RequiredArgsConstructor
public class NotificationCommandService {
    private final NotificationJpaRepository notificationRepository;
    private final NotificationPersistenceService persistenceService;

    public StoredNotification createIfAbsent(CreateNotificationCommand command) {
        try {
            return persistenceService.createIfAbsent(command);
        } catch (DataIntegrityViolationException exception) {
            return persistenceService.createIfAbsent(command);
        }
    }

    @Transactional
    public void markRead(UUID requesterId, UUID notificationId) {
        Notification notification = notificationRepository.findById(notificationId)
            .orElseThrow(() -> new NotificationException(NotificationErrorCode.NOTIFICATION_NOT_FOUND));
        if (!notification.getRecipientId().equals(requesterId)) {
            throw new NotificationException(NotificationErrorCode.NOTIFICATION_ACCESS_DENIED);
        }
        notification.markRead(Instant.now());
    }

    @Transactional
    public int markAllRead(UUID requesterId) {
        return notificationRepository.markAllUnreadAsRead(requesterId, Instant.now());
    }
}
