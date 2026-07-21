package com.prompthub.settlement.application.dto;

import com.prompthub.settlement.domain.model.SettlementPeriod;
import java.util.UUID;

public record CalculateSettlementCommand(
        UUID settlementBatchId,
        UUID sellerId,
        SettlementPeriod period
) {
}
