package com.prompthub.admin.settlement.domain.repository;

import com.prompthub.admin.settlement.domain.model.enums.PayoutStatus;
import com.prompthub.admin.settlement.domain.model.enums.SettlementStatus;
import java.math.BigDecimal;

public record SettlementStatusAggregate(
	SettlementStatus settlementStatus,
	PayoutStatus payoutStatus,
	BigDecimal sumSettlementTotal,
	long count
) {
}
