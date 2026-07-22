package com.prompthub.admin.settlement.domain.repository;

import java.time.YearMonth;
import java.util.List;

public interface SettlementQueryRepository {

	List<SettlementStatusAggregate> aggregateByStatus();

	List<SettlementStatusAggregate> aggregateByStatus(YearMonth settlementMonth);
}
