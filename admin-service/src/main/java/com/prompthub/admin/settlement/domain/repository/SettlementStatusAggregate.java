package com.prompthub.admin.settlement.domain.repository;

import com.prompthub.admin.settlement.domain.model.enums.SettlementDisplayStatus;
import java.math.BigDecimal;

public record SettlementStatusAggregate(
	SettlementDisplayStatus status,
	BigDecimal sumSettlementTotal,
	long count
) {
}
