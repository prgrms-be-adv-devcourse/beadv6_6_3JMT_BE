package com.prompthub.settlement.application.dto;

import java.time.YearMonth;
import java.util.UUID;

public record CalculateSettlementCommand(
        UUID settlementBatchId,
        UUID sellerId,
        YearMonth period
) {
}
