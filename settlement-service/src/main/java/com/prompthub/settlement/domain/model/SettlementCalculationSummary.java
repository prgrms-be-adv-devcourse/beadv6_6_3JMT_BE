package com.prompthub.settlement.domain.model;

import java.math.BigDecimal;
import java.util.Objects;

public record SettlementCalculationSummary(
        int productCount,
        BigDecimal totalAmount,
        BigDecimal refundAmount,
        BigDecimal feeTotalAmount,
        BigDecimal settlementTotalAmount
) {

    public SettlementCalculationSummary {
        totalAmount = Objects.requireNonNull(totalAmount, "totalAmount는 필수입니다.");
        refundAmount = Objects.requireNonNull(refundAmount, "refundAmount는 필수입니다.");
        feeTotalAmount = Objects.requireNonNull(feeTotalAmount, "feeTotalAmount는 필수입니다.");
        settlementTotalAmount = Objects.requireNonNull(
                settlementTotalAmount, "settlementTotalAmount는 필수입니다.");
    }

    public static SettlementCalculationSummary from(Settlement settlement) {
        Objects.requireNonNull(settlement, "settlement는 필수입니다.");
        return new SettlementCalculationSummary(
                settlement.getProductCount(),
                settlement.getTotalAmount(),
                settlement.getRefundAmount(),
                settlement.getFeeTotalAmount(),
                settlement.getSettlementTotalAmount());
    }
}
