package com.prompthub.user.sellersettlement.application.dto;

import com.prompthub.user.sellersettlement.domain.model.SettlementDisplayStatus;
import java.time.YearMonth;
import java.util.UUID;

public record SellerSettlementListQuery(
        UUID sellerId,
        SettlementDisplayStatus status,
        YearMonth period,
        int page,
        int size
) {
}
