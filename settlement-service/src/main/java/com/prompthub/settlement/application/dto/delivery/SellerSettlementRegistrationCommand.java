package com.prompthub.settlement.application.dto.delivery;

import com.prompthub.settlement.domain.model.calculation.SettlementLineType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record SellerSettlementRegistrationCommand(
        UUID deliveryRequestId, UUID settlementId, UUID sellerId,
        LocalDate periodStart, LocalDate periodEnd, int productCount,
        BigDecimal grossSalesAmount, BigDecimal refundAmount,
        BigDecimal feeTotalAmount, BigDecimal settlementTotalAmount,
        LocalDateTime calculatedAt, List<Detail> details) {

    public SellerSettlementRegistrationCommand {
        details = List.copyOf(details);
    }

    public record Detail(
            UUID settlementDetailId, UUID settlementSourceLineId,
            UUID orderProductId, SettlementLineType lineType,
            BigDecimal lineAmount, BigDecimal feeRate, BigDecimal feeAmount,
            BigDecimal lineSettlementAmount, LocalDateTime occurredAt) {
    }
}
