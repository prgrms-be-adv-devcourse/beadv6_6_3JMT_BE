package com.prompthub.settlement.application.usecase;

import com.prompthub.settlement.application.dto.SettlementListQuery;
import com.prompthub.settlement.presentation.dto.response.SettlementListResponse;
import com.prompthub.settlement.presentation.dto.response.SettlementSummaryResponse;

public interface SettlementUseCase {

    SettlementSummaryResponse getSummary();

    SettlementListResponse getList(SettlementListQuery query);
}
