package com.prompthub.settlement.application.service;

import com.prompthub.settlement.application.usecase.OutboxEventUseCase;
import com.prompthub.settlement.domain.repository.OutboxEventRepository;
import com.prompthub.settlement.domain.repository.OutboxEventRepository.OutboxCandidate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class OutboxEventApplicationService implements OutboxEventUseCase {

    private final OutboxEventRepository repository;
    private final OutboxEventPublishService publishService;
    private final int pageSize;

    public OutboxEventApplicationService(
            OutboxEventRepository repository,
            OutboxEventPublishService publishService,
            @Value("${settlement.outbox.page-size:100}") int pageSize) {
        this.repository = repository;
        this.publishService = publishService;
        this.pageSize = pageSize;
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
            publish(candidates);
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
            publish(candidates);
            OutboxCandidate last = candidates.getLast();
            cursorOccurredAt = last.occurredAt();
            cursorEventId = last.eventId();
        }
    }

    @Override
    public void redrive(UUID eventId) {
        publishService.redrive(eventId);
    }

    private void publish(List<OutboxCandidate> candidates) {
        candidates.forEach(candidate -> publishService.publish(candidate.eventId()));
    }
}
