package com.prompthub.admin.settlement.dto;

import com.prompthub.admin.settlement.entity.enums.SettlementDisplayStatus;
import java.time.YearMonth;

public record SettlementListQuery(
	SettlementDisplayStatus status,
	YearMonth settlementMonth,
	int page,
	int size
) {
}
