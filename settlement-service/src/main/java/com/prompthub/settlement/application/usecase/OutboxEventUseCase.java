package com.prompthub.settlement.application.usecase;

import java.time.LocalDateTime;
import java.util.UUID;

public interface OutboxEventUseCase {

    void flushPendingBefore(LocalDateTime attemptedBefore);

    void flushBatch(UUID settlementBatchId);

    void redrive(UUID eventId);
}
