package com.prompthub.settlement.domain.repository;

import java.math.BigDecimal;
import java.util.Objects;

public record SettlementSourceAggregate(
        long paidCount,
        BigDecimal paidAmount,
        long refundCount,
        BigDecimal refundAmount) {

    public SettlementSourceAggregate {
        if (paidCount < 0 || refundCount < 0) {
            throw new IllegalArgumentException("원천 집계 건수는 0 이상이어야 합니다.");
        }
        Objects.requireNonNull(paidAmount, "결제 원천 합계는 필수입니다.");
        Objects.requireNonNull(refundAmount, "환불 원천 합계는 필수입니다.");
    }

    public static SettlementSourceAggregate zero() {
        return new SettlementSourceAggregate(0, BigDecimal.ZERO, 0, BigDecimal.ZERO);
    }
}
