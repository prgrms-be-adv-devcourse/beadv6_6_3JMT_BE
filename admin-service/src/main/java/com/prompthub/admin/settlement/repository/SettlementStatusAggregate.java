package com.prompthub.admin.settlement.repository;

import com.prompthub.admin.settlement.entity.enums.SettlementDisplayStatus;
import java.math.BigDecimal;

public record SettlementStatusAggregate(
	SettlementDisplayStatus status,
	BigDecimal sumSettlementTotal,
	long count
) {
}
