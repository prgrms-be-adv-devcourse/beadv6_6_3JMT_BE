package com.prompthub.settlement.application.service;

import com.prompthub.settlement.application.port.SettlementEventPublisher;
import com.prompthub.settlement.domain.model.OutboxEvent;
import com.prompthub.settlement.domain.repository.OutboxEventRepository;
import com.prompthub.settlement.global.exception.SettlementErrorCode;
import com.prompthub.settlement.global.exception.SettlementException;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxEventPublishService {

    private final OutboxEventRepository repository;
    private final SettlementEventPublisher publisher;
    private final int maxRetryCount;

    public OutboxEventPublishService(
            OutboxEventRepository repository,
            SettlementEventPublisher publisher,
            @Value("${settlement.outbox.max-retry-count:3}") int maxRetryCount) {
        this.repository = repository;
        this.publisher = publisher;
        this.maxRetryCount = maxRetryCount;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publish(UUID eventId) {
        OutboxEvent event = find(eventId);
        if (!event.isPending()) {
            return;
        }
        publishPending(event);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void redrive(UUID eventId) {
        OutboxEvent event = find(eventId);
        event.requeueForRedrive();
        publishPending(event);
    }

    private void publishPending(OutboxEvent event) {
        LocalDateTime attemptedAt = LocalDateTime.now();
        try {
            publisher.publish(event.getTopic(), event.getAggregateId(), event.getPayload());
            event.markPublished(attemptedAt);
        } catch (SettlementException exception) {
            event.recordPublishFailure(resolveFailureReason(exception), attemptedAt, maxRetryCount);
        }
    }

    private OutboxEvent find(UUID eventId) {
        return repository.findById(eventId)
                .orElseThrow(() -> new SettlementException(SettlementErrorCode.OUTBOX_EVENT_NOT_FOUND));
    }

    private String resolveFailureReason(SettlementException exception) {
        if (exception.getCause() != null && exception.getCause().getMessage() != null) {
            return exception.getCause().getMessage();
        }
        return exception.getMessage();
    }
}
