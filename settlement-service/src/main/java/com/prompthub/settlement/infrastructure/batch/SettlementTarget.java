package com.prompthub.settlement.infrastructure.batch;

import java.time.YearMonth;
import java.util.UUID;

public record SettlementTarget(
        UUID sellerId,
        YearMonth period,
        UUID settlementBatchId
) {
}
