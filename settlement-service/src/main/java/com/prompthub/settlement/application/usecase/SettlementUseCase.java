package com.prompthub.settlement.application.usecase;

import com.prompthub.settlement.application.dto.SettlementListQuery;
import com.prompthub.settlement.application.dto.SettlementListResult;
import com.prompthub.settlement.application.dto.SettlementSummaryResult;

public interface SettlementUseCase {

    SettlementSummaryResult getSummary();

    SettlementListResult getList(SettlementListQuery query);
}
