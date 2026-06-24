package com.prompthub.settlement.domain.repository;

import java.util.List;

public interface SettlementSummaryQueryRepository {

    List<SettlementStatusAggregate> aggregateByStatus();
}
