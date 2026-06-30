package com.prompthub.settlement.application.dto;

import com.prompthub.settlement.domain.model.enums.SettlementDisplayStatus;
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
