package com.prompthub.user.sellersettlement.application.event;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record SettlementCreatedPayload(
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
}
