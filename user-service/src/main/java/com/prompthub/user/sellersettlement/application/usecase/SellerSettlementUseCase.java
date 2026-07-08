package com.prompthub.user.sellersettlement.application.usecase;

import com.prompthub.user.sellersettlement.application.dto.SellerSettlementListQuery;
import com.prompthub.user.sellersettlement.application.dto.SellerSettlementListResult;
import com.prompthub.user.sellersettlement.application.dto.SellerSettlementResult;
import java.util.UUID;

public interface SellerSettlementUseCase {

    SellerSettlementListResult getMySettlements(SellerSettlementListQuery query);

    SellerSettlementResult requestPayout(UUID sellerId, UUID settlementId);
}
