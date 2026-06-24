package com.prompthub.settlement.infrastructure.batch.model;

import java.time.YearMonth;
import java.util.UUID;

public record SettlementTarget(
        UUID sellerId,
        YearMonth period,
        UUID settlementBatchId
) {
}
