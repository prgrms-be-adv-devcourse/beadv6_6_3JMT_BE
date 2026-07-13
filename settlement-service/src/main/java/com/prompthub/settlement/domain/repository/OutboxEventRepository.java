package com.prompthub.settlement.domain.repository;

import com.prompthub.settlement.domain.model.OutboxEvent;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OutboxEventRepository {

    OutboxEvent save(OutboxEvent event);

    Optional<OutboxEvent> findById(UUID eventId);

    List<OutboxCandidate> findPendingBefore(
            LocalDateTime attemptedBefore,
            LocalDateTime cursorOccurredAt,
            UUID cursorEventId,
            int limit);

    List<OutboxCandidate> findPendingByBatchId(
            UUID settlementBatchId,
            LocalDateTime cursorOccurredAt,
            UUID cursorEventId,
            int limit);

    record OutboxCandidate(UUID eventId, LocalDateTime occurredAt) {
    }
}
