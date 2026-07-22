package com.prompthub.settlement.application.dto;

import java.util.Objects;
import java.util.UUID;

public record RestartSettlementBatchCommand(UUID batchId, UUID actorId) {

    public RestartSettlementBatchCommand {
        Objects.requireNonNull(batchId, "batchId는 필수입니다.");
        Objects.requireNonNull(actorId, "actorId는 필수입니다.");
    }
}
