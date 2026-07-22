package com.prompthub.user.sellersettlement.application.dto;

import com.prompthub.user.sellersettlement.domain.model.enums.SettlementDisplayStatus;
import java.time.YearMonth;
import java.util.UUID;

public record SellerSettlementListQuery(
        UUID sellerId,
        SettlementDisplayStatus status,
        YearMonth settlementMonth,
        int page,
        int size
) {
}
