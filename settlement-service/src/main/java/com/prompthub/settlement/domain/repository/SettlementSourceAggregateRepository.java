package com.prompthub.settlement.domain.repository;

import com.prompthub.settlement.domain.model.batch.SettlementPeriod;

public interface SettlementSourceAggregateRepository {

    SettlementSourceAggregate aggregate(SettlementPeriod period);
}
