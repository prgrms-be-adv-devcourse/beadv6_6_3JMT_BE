package com.prompthub.settlement.infrastructure.batch.settlement;

import com.prompthub.settlement.domain.model.SettlementPeriod;
import java.util.UUID;

public record SettlementTarget(
        UUID sellerId,
        SettlementPeriod period,
        UUID settlementBatchId
) {
}
