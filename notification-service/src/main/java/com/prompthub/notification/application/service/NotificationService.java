package com.prompthub.notification.application.service;

import com.prompthub.notification.application.command.CreateNotificationCommand;
import com.prompthub.notification.application.dto.NotificationReadResponse;
import com.prompthub.notification.application.dto.NotificationResponse;
import com.prompthub.notification.application.dto.ReadAllNotificationsResponse;
import com.prompthub.notification.application.dto.UnreadNotificationCountResponse;
import com.prompthub.notification.application.dto.NotificationReplayResult;
import com.prompthub.notification.application.dto.NotificationSseEvent;
import com.prompthub.notification.application.dto.NotificationSsePayload;
import com.prompthub.notification.application.event.NotificationCreatedEvent;
import com.prompthub.notification.application.usecase.NotificationSettingUseCase;
import com.prompthub.notification.application.usecase.NotificationUseCase;
import com.prompthub.notification.domain.enums.NotificationCategory;
import com.prompthub.notification.domain.model.Notification;
import com.prompthub.notification.domain.repository.NotificationRepository;
import com.prompthub.notification.global.exception.NotificationCustomException;
import com.prompthub.notification.global.exception.NotificationErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationService implements NotificationUseCase {

    private final NotificationRepository notificationRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final NotificationSettingUseCase notificationSettingUseCase;

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationResponse> getNotifications(UUID recipientId, NotificationCategory category, int page, int size) {
        PageRequest pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Notification> notifications = category == null
            ? notificationRepository.findByRecipientIdAndExpiresAtAfter(recipientId, Instant.now(), pageable)
            : notificationRepository.findByRecipientIdAndCategoryAndExpiresAtAfter(recipientId, category, Instant.now(), pageable);
        return notifications.map(NotificationResponse::from);
    }

    @Override
    @Transactional(readOnly = true)
    public UnreadNotificationCountResponse getUnreadCount(UUID recipientId) {
        return new UnreadNotificationCountResponse(notificationRepository.countByRecipientIdAndReadFalseAndExpiresAtAfter(recipientId, Instant.now()));
    }

    @Override
    @Transactional
    public NotificationReadResponse readNotification(UUID recipientId, UUID notificationId) {
        Notification notification = findOwnedActiveNotification(recipientId, notificationId);
        notification.markRead(Instant.now());
        return new NotificationReadResponse(notification.getId(), notification.isRead(), notification.getReadAt());
    }

    @Override
    @Transactional
    public ReadAllNotificationsResponse readAllNotifications(UUID recipientId) {
        Instant now = Instant.now();
        long updated = notificationRepository.markAllUnreadAsRead(recipientId, now, now);
        return new ReadAllNotificationsResponse(updated, now);
    }

    @Override
    @Transactional
    public Optional<NotificationResponse> createNotification(CreateNotificationCommand command) {
        if (!notificationSettingUseCase.canReceive(command.recipientId(), command.category())) {
            return Optional.empty();
        }
        Notification notification = Notification.create(command.recipientId(), command.type(), command.category(), command.title(),
            command.content(), command.linkUrl(), command.referenceType(), command.referenceId(), command.occurredAt(), command.deduplicationKey());
        Notification saved = notificationRepository.save(notification);
        eventPublisher.publishEvent(new NotificationCreatedEvent(saved.getId(), saved.getRecipientId()));
        return Optional.of(NotificationResponse.from(saved));
    }

    @Override
    @Transactional
    public long deleteExpiredNotifications() {
        return notificationRepository.deleteByExpiresAtBefore(Instant.now());
    }

    @Override
    @Transactional(readOnly = true)
    public NotificationReplayResult getReplay(UUID recipientId, UUID lastEventId) {
        if (lastEventId == null) return new NotificationReplayResult(List.of(), false);
        Instant now = Instant.now();
        Notification cursor = findOwnedActiveNotification(recipientId, lastEventId);
        List<Notification> candidates = notificationRepository.findReplayCandidates(recipientId, now, cursor.getCreatedAt(), cursor.getId(), 101);
        if (candidates.size() > NotificationReplayResult.REPLAY_LIMIT) return new NotificationReplayResult(List.of(), true);
        long unreadCount = notificationRepository.countByRecipientIdAndReadFalseAndExpiresAtAfter(recipientId, now);
        return new NotificationReplayResult(candidates.stream().map(n -> new NotificationSseEvent(n.getId(),
            new NotificationSsePayload(n.getId(), n.getType().name(), n.getTitle(), n.getContent(), unreadCount))).toList(), false);
    }

    private Notification findOwnedActiveNotification(UUID recipientId, UUID notificationId) {
        return notificationRepository.findByIdAndRecipientIdAndExpiresAtAfter(notificationId, recipientId, Instant.now())
            .orElseThrow(() -> new NotificationCustomException(NotificationErrorCode.NOTIFICATION_NOT_FOUND));
    }
}
