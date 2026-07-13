package com.prompthub.settlement.infrastructure.persistence.outbox;

import com.prompthub.settlement.domain.model.OutboxEvent;
import com.prompthub.settlement.domain.model.enums.OutboxEventStatus;
import com.prompthub.settlement.domain.repository.OutboxEventRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class OutboxEventRepositoryAdapter implements OutboxEventRepository {

    private final OutboxEventJpaRepository jpaRepository;

    @Override
    public OutboxEvent save(OutboxEvent event) {
        return jpaRepository.save(event);
    }

    @Override
    public Optional<OutboxEvent> findById(UUID eventId) {
        return jpaRepository.findById(eventId);
    }

    @Override
    public List<OutboxCandidate> findPendingBefore(
            LocalDateTime attemptedBefore,
            LocalDateTime cursorOccurredAt,
            UUID cursorEventId,
            int limit) {
        List<OutboxEvent> events = cursorOccurredAt == null
                ? jpaRepository.findPendingBefore(
                        OutboxEventStatus.PENDING,
                        attemptedBefore,
                        PageRequest.of(0, limit))
                : jpaRepository.findPendingBeforeAfterCursor(
                        OutboxEventStatus.PENDING,
                        attemptedBefore,
                        cursorOccurredAt,
                        cursorEventId,
                        PageRequest.of(0, limit));
        return toCandidates(events);
    }

    @Override
    public List<OutboxCandidate> findPendingByBatchId(
            UUID settlementBatchId,
            LocalDateTime cursorOccurredAt,
            UUID cursorEventId,
            int limit) {
        List<OutboxEvent> events = cursorOccurredAt == null
                ? jpaRepository.findPendingByBatchId(
                        settlementBatchId,
                        OutboxEventStatus.PENDING,
                        PageRequest.of(0, limit))
                : jpaRepository.findPendingByBatchIdAfterCursor(
                        settlementBatchId,
                        OutboxEventStatus.PENDING,
                        cursorOccurredAt,
                        cursorEventId,
                        PageRequest.of(0, limit));
        return toCandidates(events);
    }

    private List<OutboxCandidate> toCandidates(List<OutboxEvent> events) {
        return events.stream()
                .map(event -> new OutboxCandidate(event.getEventId(), event.getOccurredAt()))
                .toList();
    }
}
