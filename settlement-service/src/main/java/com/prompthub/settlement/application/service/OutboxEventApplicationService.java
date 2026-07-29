package com.prompthub.settlement.application.service;

import com.prompthub.settlement.application.event.SettlementCreatedEvent;
import com.prompthub.settlement.application.port.OutboxEventAppender;
import com.prompthub.settlement.application.port.RequiresNewTransactionExecutor;
import com.prompthub.settlement.application.port.SettlementEventPublisher;
import com.prompthub.settlement.application.usecase.OutboxEventUseCase;
import com.prompthub.settlement.domain.model.SettlementOutboxEvent;
import com.prompthub.settlement.domain.repository.OutboxEventRepository;
import com.prompthub.settlement.domain.repository.OutboxEventRepository.OutboxCandidate;
import com.prompthub.settlement.global.exception.SettlementErrorCode;
import com.prompthub.settlement.global.exception.SettlementException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxEventApplicationService implements OutboxEventUseCase {

    private final OutboxEventRepository repository;
    private final OutboxEventAppender appender;
    private final SettlementEventPublisher publisher;
    private final RequiresNewTransactionExecutor transactionExecutor;
    private final int pageSize;
    private final int maxRetryCount;

    public OutboxEventApplicationService(
            OutboxEventRepository repository,
            OutboxEventAppender appender,
            SettlementEventPublisher publisher,
            RequiresNewTransactionExecutor transactionExecutor,
            @Value("${settlement.outbox.page-size:100}") int pageSize,
            @Value("${settlement.outbox.max-retry-count:3}") int maxRetryCount) {
        this.repository = repository;
        this.appender = appender;
        this.publisher = publisher;
        this.transactionExecutor = transactionExecutor;
        this.pageSize = pageSize;
        this.maxRetryCount = maxRetryCount;
    }

    @Override
    @Transactional
    public void appendSettlementCreated(
            UUID settlementBatchId,
            SettlementCreatedEvent event) {
        appender.appendSettlementCreated(settlementBatchId, event);
    }

    @Override
    public void flushPendingBefore(LocalDateTime attemptedBefore) {
        LocalDateTime cursorOccurredAt = null;
        UUID cursorEventId = null;

        while (true) {
            List<OutboxCandidate> candidates = repository.findPendingBefore(
                    attemptedBefore,
                    cursorOccurredAt,
                    cursorEventId,
                    pageSize);
            if (candidates.isEmpty()) {
                return;
            }
            candidates.forEach(candidate -> publish(candidate.eventId()));
            OutboxCandidate last = candidates.getLast();
            cursorOccurredAt = last.occurredAt();
            cursorEventId = last.eventId();
        }
    }

    @Override
    public void flushBatch(UUID settlementBatchId) {
        LocalDateTime cursorOccurredAt = null;
        UUID cursorEventId = null;

        while (true) {
            List<OutboxCandidate> candidates = repository.findPendingByBatchId(
                    settlementBatchId,
                    cursorOccurredAt,
                    cursorEventId,
                    pageSize);
            if (candidates.isEmpty()) {
                return;
            }
            candidates.forEach(candidate -> publish(candidate.eventId()));
            OutboxCandidate last = candidates.getLast();
            cursorOccurredAt = last.occurredAt();
            cursorEventId = last.eventId();
        }
    }

    @Override
    public void redrive(UUID eventId) {
        transactionExecutor.execute(() -> {
            SettlementOutboxEvent event = find(eventId);
            event.requeueForRedrive();
            publishPending(event);
        });
    }

    public void publish(UUID eventId) {
        transactionExecutor.execute(() -> {
            SettlementOutboxEvent event = find(eventId);
            if (!event.isPending()) {
                return;
            }
            publishPending(event);
        });
    }

    private void publishPending(SettlementOutboxEvent event) {
        LocalDateTime attemptedAt = LocalDateTime.now();
        try {
            publisher.publish(event.getTopic(), event.getAggregateId(), event.getPayload());
            event.markPublished(attemptedAt);
        } catch (SettlementException exception) {
            event.recordPublishFailure(
                    resolveFailureReason(exception),
                    attemptedAt,
                    maxRetryCount);
        }
    }

    private SettlementOutboxEvent find(UUID eventId) {
        return repository.findById(eventId)
                .orElseThrow(() -> new SettlementException(
                        SettlementErrorCode.OUTBOX_EVENT_NOT_FOUND));
    }

    private String resolveFailureReason(SettlementException exception) {
        if (exception.getCause() != null && exception.getCause().getMessage() != null) {
            return exception.getCause().getMessage();
        }
        return exception.getMessage();
    }
}
