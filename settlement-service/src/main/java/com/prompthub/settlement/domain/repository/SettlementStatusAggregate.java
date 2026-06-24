package com.prompthub.settlement.domain.repository;

import com.prompthub.settlement.domain.model.enums.PayoutStatus;
import com.prompthub.settlement.domain.model.enums.SettlementStatus;
import java.math.BigDecimal;

public record SettlementStatusAggregate(
        SettlementStatus settlementStatus,
        PayoutStatus payoutStatus,
        BigDecimal sumSettlementTotal,
        long count
) {
}
