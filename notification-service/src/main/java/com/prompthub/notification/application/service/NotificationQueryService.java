package com.prompthub.notification.application.service;

import com.prompthub.notification.domain.model.Notification;
import com.prompthub.notification.infra.persistence.NotificationJpaRepository;
import java.util.UUID;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationQueryService {
    private final NotificationJpaRepository notificationRepository;

    @Transactional(readOnly = true)
    public NotificationPage findPage(UUID recipientId, int page, int size) {
        Page<Notification> notifications = notificationRepository.findByRecipientIdOrderBySequenceDesc(recipientId, PageRequest.of(page, size));
        return new NotificationPage(notifications.map(this::toItem).toList(), notifications.getTotalElements(), notifications.hasNext());
    }

    @Transactional(readOnly = true)
    public long countUnread(UUID recipientId) {
        return notificationRepository.countByRecipientIdAndReadAtIsNull(recipientId);
    }

    @Transactional(readOnly = true)
    public List<NotificationItem> findCreatedAfter(UUID recipientId, long sequence) {
        return notificationRepository.findByRecipientIdAndSequenceGreaterThanOrderBySequenceAsc(recipientId, sequence)
            .stream()
            .map(this::toItem)
            .toList();
    }

    private NotificationItem toItem(Notification notification) {
        return new NotificationItem(
            notification.getId(), notification.getSequence(), notification.getType(), notification.getTitle(), notification.getMessage(),
            notification.getReferenceType(), notification.getReferenceId(), notification.getReadAt() != null, notification.getCreatedAt()
        );
    }
}
