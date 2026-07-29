package com.prompthub.user.sellersettlement.application.dto;

import com.prompthub.user.sellersettlement.domain.model.enums.SellerSettlementLineType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record RegisterSellerSettlementCommand(
        UUID deliveryRequestId,
        UUID settlementId,
        UUID sellerId,
        LocalDate periodStart,
        LocalDate periodEnd,
        int productCount,
        BigDecimal grossSalesAmount,
        BigDecimal refundAmount,
        BigDecimal feeTotalAmount,
        BigDecimal settlementTotalAmount,
        LocalDateTime calculatedAt,
        List<Detail> details
) {

    public RegisterSellerSettlementCommand {
        Objects.requireNonNull(deliveryRequestId, "deliveryRequestId는 필수입니다.");
        Objects.requireNonNull(settlementId, "settlementId는 필수입니다.");
        Objects.requireNonNull(sellerId, "sellerId는 필수입니다.");
        Objects.requireNonNull(periodStart, "periodStart는 필수입니다.");
        Objects.requireNonNull(periodEnd, "periodEnd는 필수입니다.");
        Objects.requireNonNull(grossSalesAmount, "grossSalesAmount는 필수입니다.");
        Objects.requireNonNull(refundAmount, "refundAmount는 필수입니다.");
        Objects.requireNonNull(feeTotalAmount, "feeTotalAmount는 필수입니다.");
        Objects.requireNonNull(settlementTotalAmount, "settlementTotalAmount는 필수입니다.");
        Objects.requireNonNull(calculatedAt, "calculatedAt은 필수입니다.");
        details = List.copyOf(Objects.requireNonNull(details, "details는 필수입니다."));
        if (periodStart.isAfter(periodEnd)) {
            throw new IllegalArgumentException("정산 시작일은 종료일 이후일 수 없습니다.");
        }
        if (productCount < 0) {
            throw new IllegalArgumentException("productCount는 음수일 수 없습니다.");
        }
        HashSet<UUID> ids = new HashSet<>();
        if (details.stream().map(Detail::settlementDetailId).anyMatch(id -> !ids.add(id))) {
            throw new IllegalArgumentException("settlementDetailId는 중복될 수 없습니다.");
        }
    }

    public record Detail(
            UUID settlementDetailId,
            UUID settlementSourceLineId,
            UUID orderProductId,
            SellerSettlementLineType lineType,
            BigDecimal lineAmount,
            BigDecimal feeRate,
            BigDecimal feeAmount,
            BigDecimal lineSettlementAmount,
            LocalDateTime occurredAt
    ) {

        public Detail {
            Objects.requireNonNull(settlementDetailId, "settlementDetailId는 필수입니다.");
            Objects.requireNonNull(
                    settlementSourceLineId, "settlementSourceLineId는 필수입니다.");
            Objects.requireNonNull(orderProductId, "orderProductId는 필수입니다.");
            Objects.requireNonNull(lineType, "lineType은 필수입니다.");
            Objects.requireNonNull(lineAmount, "lineAmount는 필수입니다.");
            Objects.requireNonNull(feeRate, "feeRate는 필수입니다.");
            Objects.requireNonNull(feeAmount, "feeAmount는 필수입니다.");
            Objects.requireNonNull(lineSettlementAmount, "lineSettlementAmount는 필수입니다.");
            Objects.requireNonNull(occurredAt, "occurredAt은 필수입니다.");
        }
    }
}
