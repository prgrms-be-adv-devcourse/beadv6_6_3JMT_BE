package com.prompthub.user.sellersettlement.application.usecase;

import com.prompthub.user.sellersettlement.application.dto.SellerSettlementListQuery;
import com.prompthub.user.sellersettlement.presentation.dto.response.SellerSettlementListResponse;
import com.prompthub.user.sellersettlement.presentation.dto.response.SellerSettlementStatusResponse;
import java.util.UUID;

public interface SellerSettlementUseCase {

    SellerSettlementListResponse getMySettlements(SellerSettlementListQuery query);

    SellerSettlementStatusResponse requestPayout(UUID sellerId, UUID settlementId);
}
