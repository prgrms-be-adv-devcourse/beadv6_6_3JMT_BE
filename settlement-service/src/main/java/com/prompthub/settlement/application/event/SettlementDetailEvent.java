package com.prompthub.settlement.application.event;

import com.prompthub.settlement.domain.model.SettlementDetail;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record SettlementDetailEvent(
        UUID settlementDetailId,
        UUID orderProductId,
        String lineType,
        BigDecimal lineAmount,
        BigDecimal feeRate,
        BigDecimal feeAmount,
        BigDecimal lineSettlementAmount,
        LocalDateTime occurredAt
) {

    public static SettlementDetailEvent from(SettlementDetail detail) {
        return new SettlementDetailEvent(
                detail.getId(),
                detail.getOrderProductId(),
                detail.getLineType().name(),
                detail.getLineAmount(),
                detail.getFeeRate(),
                detail.getFeeAmount(),
                detail.getLineSettlementAmount(),
                detail.getOccurredAt());
    }
}
