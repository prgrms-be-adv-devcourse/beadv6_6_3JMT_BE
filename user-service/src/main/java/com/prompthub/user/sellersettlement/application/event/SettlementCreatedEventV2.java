package com.prompthub.user.sellersettlement.application.event;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

public record SettlementCreatedEventV2(
        int payloadVersion,
        UUID settlementId,
        UUID sellerId,
        LocalDate periodStart,
        LocalDate periodEnd,
        int productCount,
        BigDecimal totalAmount,
        BigDecimal settlementTotalAmount,
        BigDecimal feeTotalAmount,
        BigDecimal refundAmount,
        LocalDateTime calculatedAt,
        List<SettlementDetailEvent> details
) {

    public SettlementCreatedEventV2 {
        if (payloadVersion != 2) {
            throw new IllegalArgumentException("V2 payloadVersion은 2여야 합니다.");
        }
        Objects.requireNonNull(settlementId, "settlementId는 필수입니다.");
        Objects.requireNonNull(sellerId, "sellerId는 필수입니다.");
        Objects.requireNonNull(periodStart, "periodStart는 필수입니다.");
        Objects.requireNonNull(periodEnd, "periodEnd는 필수입니다.");
        Objects.requireNonNull(totalAmount, "totalAmount는 필수입니다.");
        Objects.requireNonNull(settlementTotalAmount, "settlementTotalAmount는 필수입니다.");
        Objects.requireNonNull(feeTotalAmount, "feeTotalAmount는 필수입니다.");
        Objects.requireNonNull(refundAmount, "refundAmount는 필수입니다.");
        Objects.requireNonNull(calculatedAt, "calculatedAt은 필수입니다.");
        details = List.copyOf(Objects.requireNonNull(details, "details는 필수입니다."));
    }

    public void validateContract() {
        validateAggregates(
                productCount,
                totalAmount,
                settlementTotalAmount,
                feeTotalAmount,
                refundAmount,
                details
        );
    }

    private static void validateAggregates(
            int productCount,
            BigDecimal totalAmount,
            BigDecimal settlementTotalAmount,
            BigDecimal feeTotalAmount,
            BigDecimal refundAmount,
            List<SettlementDetailEvent> details
    ) {
        List<SettlementDetailEvent> sales = details.stream()
                .filter(detail -> detail.lineType().equals("SALE"))
                .toList();
        List<SettlementDetailEvent> refunds = details.stream()
                .filter(detail -> detail.lineType().equals("REFUND"))
                .toList();
        if (productCount != sales.size()) {
            throw new IllegalArgumentException("productCount가 SALE Detail 수와 일치하지 않습니다.");
        }
        requireSameAmount(totalAmount, sum(sales, SettlementDetailEvent::lineAmount), "totalAmount");
        requireSameAmount(settlementTotalAmount, sum(details, SettlementDetailEvent::lineSettlementAmount),
                "settlementTotalAmount");
        requireSameAmount(feeTotalAmount, sum(details, SettlementDetailEvent::feeAmount), "feeTotalAmount");
        requireSameAmount(refundAmount, sum(refunds, SettlementDetailEvent::lineAmount).abs(), "refundAmount");
    }

    private static BigDecimal sum(
            List<SettlementDetailEvent> details,
            Function<SettlementDetailEvent, BigDecimal> field
    ) {
        return details.stream().map(field).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static void requireSameAmount(BigDecimal expected, BigDecimal actual, String fieldName) {
        if (expected.compareTo(actual) != 0) {
            throw new IllegalArgumentException(fieldName + "가 Detail 집계값과 일치하지 않습니다.");
        }
    }
}
