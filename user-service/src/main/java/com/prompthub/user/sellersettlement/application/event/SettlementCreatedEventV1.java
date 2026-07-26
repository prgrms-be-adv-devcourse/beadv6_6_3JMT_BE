package com.prompthub.user.sellersettlement.application.event;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record SettlementCreatedEventV1(
        Integer payloadVersion,
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

    public SettlementCreatedEventV1 {
        if (payloadVersion != null && payloadVersion != 1) {
            throw new IllegalArgumentException("V1 payloadVersion은 없거나 1이어야 합니다.");
        }
    }
}
