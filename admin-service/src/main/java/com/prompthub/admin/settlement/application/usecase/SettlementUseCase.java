package com.prompthub.admin.settlement.application.usecase;

import com.prompthub.admin.settlement.application.dto.SettlementListQuery;
import com.prompthub.admin.settlement.presentation.dto.response.SettlementListResponse;
import com.prompthub.admin.settlement.presentation.dto.response.SettlementResponse;
import com.prompthub.admin.settlement.presentation.dto.response.SettlementStatusResponse;
import com.prompthub.admin.settlement.presentation.dto.response.SettlementSummaryResponse;
import java.util.UUID;

public interface SettlementUseCase {

	SettlementListResponse getList(SettlementListQuery query);

	SettlementSummaryResponse getSummary();

	SettlementStatusResponse approve(UUID settlementId);

	SettlementStatusResponse hold(UUID settlementId);

	SettlementStatusResponse releaseHold(UUID settlementId);

	SettlementStatusResponse payout(UUID settlementId);

	SettlementStatusResponse payoutHold(UUID settlementId);

	SettlementStatusResponse releasePayoutHold(UUID settlementId);

	SettlementResponse cancel(UUID settlementId);
}
