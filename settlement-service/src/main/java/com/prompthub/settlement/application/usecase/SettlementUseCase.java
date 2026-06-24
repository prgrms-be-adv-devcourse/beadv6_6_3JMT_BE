package com.prompthub.settlement.application.usecase;

import com.prompthub.settlement.application.dto.SellerSettlementListQuery;
import com.prompthub.settlement.application.dto.SettlementListQuery;
import com.prompthub.settlement.presentation.dto.response.SellerSettlementListResponse;
import com.prompthub.settlement.presentation.dto.response.SellerSettlementSummaryResponse;
import com.prompthub.settlement.presentation.dto.response.SettlementListResponse;
import com.prompthub.settlement.presentation.dto.response.SettlementResponse;
import com.prompthub.settlement.presentation.dto.response.SettlementStatusResponse;
import com.prompthub.settlement.presentation.dto.response.SettlementSummaryResponse;
import java.util.UUID;

public interface SettlementUseCase {

    SettlementSummaryResponse getSummary();

    SettlementListResponse getList(SettlementListQuery query);

    SellerSettlementListResponse getMySettlements(SellerSettlementListQuery query);

    SellerSettlementSummaryResponse getMySummary(UUID sellerId);

    SettlementStatusResponse approve(UUID settlementId);

    SettlementStatusResponse hold(UUID settlementId);

    SettlementStatusResponse releaseHold(UUID settlementId);

    SettlementStatusResponse payout(UUID settlementId);

    SettlementStatusResponse payoutHold(UUID settlementId);

    SettlementStatusResponse releasePayoutHold(UUID settlementId);

    SettlementResponse cancel(UUID settlementId);

    SettlementStatusResponse requestPayout(UUID sellerId, UUID settlementId);
}
