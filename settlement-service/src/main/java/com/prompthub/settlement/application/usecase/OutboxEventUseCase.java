package com.prompthub.settlement.application.usecase;

import com.prompthub.settlement.application.event.SettlementCreatedEvent;
import java.time.LocalDateTime;
import java.util.UUID;

public interface OutboxEventUseCase {

    void appendSettlementCreated(UUID settlementBatchId, SettlementCreatedEvent event);

    void flushPendingBefore(LocalDateTime attemptedBefore);

    void flushBatch(UUID settlementBatchId);

    void redrive(UUID eventId);
}
