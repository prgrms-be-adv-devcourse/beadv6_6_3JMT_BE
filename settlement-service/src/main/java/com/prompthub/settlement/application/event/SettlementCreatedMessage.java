package com.prompthub.settlement.application.event;

import com.prompthub.settlement.domain.model.Settlement;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record SettlementCreatedMessage(
        UUID settlementId,
        UUID sellerId,
        LocalDate periodStart,
        LocalDate periodEnd,
        int productCount,
        BigDecimal totalAmount,
        BigDecimal settlementTotalAmount,
        BigDecimal feeTotalAmount,
        BigDecimal refundAmount,
        LocalDateTime calculatedAt
) {

    public static SettlementCreatedMessage from(Settlement settlement) {
        return new SettlementCreatedMessage(
                settlement.getId(),
                settlement.getSellerId(),
                settlement.getPeriodStart(),
                settlement.getPeriodEnd(),
                settlement.getProductCount(),
                settlement.getTotalAmount(),
                settlement.getSettlementTotalAmount(),
                settlement.getFeeTotalAmount(),
                settlement.getRefundAmount(),
                settlement.getCalculatedAt()
        );
    }
}
