package com.prompthub.user.sellersettlement.application.usecase;

import com.prompthub.user.sellersettlement.application.dto.SellerSettlementListQuery;
import com.prompthub.user.sellersettlement.presentation.dto.response.SellerSettlementDetailResponse;
import com.prompthub.user.sellersettlement.presentation.dto.response.SellerSettlementListResponse;
import com.prompthub.user.sellersettlement.presentation.dto.response.SellerSettlementStatusResponse;
import com.prompthub.user.sellersettlement.presentation.dto.response.SellerSettlementSummaryResponse;
import java.time.YearMonth;
import java.util.UUID;

public interface SellerSettlementUseCase {

    SellerSettlementListResponse getMySettlements(SellerSettlementListQuery query);

    SellerSettlementDetailResponse getMySettlementMonth(
            UUID sellerId, YearMonth settlementMonth);

    SellerSettlementSummaryResponse getMySummary(UUID sellerId);

    SellerSettlementStatusResponse requestPayout(UUID sellerId, UUID settlementId);
}
