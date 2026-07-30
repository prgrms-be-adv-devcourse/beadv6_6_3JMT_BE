package com.prompthub.user.sellersettlement.application.dto;

import java.math.BigDecimal;
import java.util.Objects;

public record SellerSettlementDashboardSummaryResult(
        BigDecimal totalRevenueAmount,
        BigDecimal totalSettlementAmount
) {

    public SellerSettlementDashboardSummaryResult {
        Objects.requireNonNull(totalRevenueAmount, "totalRevenueAmount");
        Objects.requireNonNull(totalSettlementAmount, "totalSettlementAmount");
    }
}
