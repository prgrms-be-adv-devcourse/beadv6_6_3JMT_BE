package com.prompthub.user.sellersettlement.application.dto;

import com.prompthub.user.sellersettlement.domain.model.SellerSettlement;
import com.prompthub.user.sellersettlement.domain.model.SellerSettlementDetail;
import com.prompthub.user.sellersettlement.domain.model.enums.SellerSettlementLineType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record RegisteredSellerSettlementSnapshot(
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

    public RegisteredSellerSettlementSnapshot {
        details = List.copyOf(details);
    }

    public static RegisteredSellerSettlementSnapshot from(SellerSettlement settlement) {
        return new RegisteredSellerSettlementSnapshot(
                settlement.getDeliveryRequestId(),
                settlement.getSettlementId(),
                settlement.getSellerId(),
                settlement.getPeriodStart(),
                settlement.getPeriodEnd(),
                settlement.getProductCount(),
                settlement.getTotalAmount(),
                settlement.getRefundAmount(),
                settlement.getFeeTotalAmount(),
                settlement.getSettlementTotalAmount(),
                settlement.getCalculatedAt(),
                settlement.getDetails().stream().map(Detail::from).toList());
    }

    public record Detail(
            UUID settlementDetailId,
            UUID orderProductId,
            SellerSettlementLineType lineType,
            BigDecimal lineAmount,
            BigDecimal feeRate,
            BigDecimal feeAmount,
            BigDecimal lineSettlementAmount,
            LocalDateTime occurredAt
    ) {

        private static Detail from(SellerSettlementDetail detail) {
            return new Detail(
                    detail.getSettlementDetailId(),
                    detail.getOrderProductId(),
                    detail.getLineType(),
                    detail.getLineAmount(),
                    detail.getFeeRate(),
                    detail.getFeeAmount(),
                    detail.getLineSettlementAmount(),
                    detail.getOccurredAt());
        }
    }
}
