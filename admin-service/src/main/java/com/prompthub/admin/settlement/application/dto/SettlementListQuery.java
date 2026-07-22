package com.prompthub.admin.settlement.application.dto;

import com.prompthub.admin.settlement.domain.model.enums.SettlementDisplayStatus;
import java.time.YearMonth;

public record SettlementListQuery(
	SettlementDisplayStatus status,
	YearMonth settlementMonth,
	int page,
	int size
) {
}
