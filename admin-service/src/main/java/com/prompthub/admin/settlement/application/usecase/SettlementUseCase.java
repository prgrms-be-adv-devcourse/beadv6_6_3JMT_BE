package com.prompthub.admin.settlement.application.usecase;

import com.prompthub.admin.settlement.application.dto.SettlementListQuery;
import com.prompthub.admin.settlement.presentation.dto.response.SettlementListResponse;

public interface SettlementUseCase {

	SettlementListResponse getList(SettlementListQuery query);
}
