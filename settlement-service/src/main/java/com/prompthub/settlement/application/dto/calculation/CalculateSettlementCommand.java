package com.prompthub.settlement.application.dto.calculation;

import com.prompthub.settlement.domain.model.batch.SettlementPeriod;
import java.util.UUID;

public record CalculateSettlementCommand(
        UUID settlementBatchId,
        UUID sellerId,
        SettlementPeriod period
) {
}
