package com.prompthub.notification.application.service;

import com.prompthub.notification.domain.model.Notification;
import com.prompthub.notification.domain.model.NotificationRecipientSequence;
import com.prompthub.notification.domain.model.ProcessedEvent;
import com.prompthub.notification.infra.persistence.NotificationJpaRepository;
import com.prompthub.notification.infra.persistence.NotificationRecipientSequenceJpaRepository;
import com.prompthub.notification.infra.persistence.ProcessedEventJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class NotificationPersistenceService {
    private final NotificationJpaRepository notificationRepository;
    private final ProcessedEventJpaRepository processedEventRepository;
    private final NotificationRecipientSequenceJpaRepository sequenceRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StoredNotification createIfAbsent(CreateNotificationCommand command) {
        return processedEventRepository.findByEventIdAndConsumerGroup(command.eventId(), command.consumerGroup())
            .map(event -> notificationRepository.findById(event.getNotificationId())
                .map(notification -> new StoredNotification(notification.getId(), notification.getSequence(), false))
                .orElseThrow(() -> new IllegalStateException("Processed notification is missing")))
            .orElseGet(() -> create(command));
    }

    private StoredNotification create(CreateNotificationCommand command) {
        NotificationRecipientSequence sequence = sequenceRepository.findByRecipientIdForUpdate(command.recipientId())
            .orElseGet(() -> sequenceRepository.saveAndFlush(new NotificationRecipientSequence(command.recipientId())));
        long nextSequence = sequence.next();
        Notification notification = notificationRepository.save(Notification.create(
            command.recipientId(), nextSequence, command.eventId(), command.type(), command.title(),
            command.message(), command.referenceType(), command.referenceId(), command.occurredAt()
        ));
        processedEventRepository.save(new ProcessedEvent(command.eventId(), command.consumerGroup(), notification.getId(), command.occurredAt()));
        return new StoredNotification(notification.getId(), notification.getSequence(), true);
    }
}
