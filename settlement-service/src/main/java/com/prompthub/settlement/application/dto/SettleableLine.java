package com.prompthub.settlement.application.dto;

import com.prompthub.settlement.domain.model.enums.SettlementSourceEventType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * order-service 에서 gRPC 로 조회한 정산 대상 라인 1건.
 * SettleableLineQueryPort 가 반환하며, SettlementSourceLine 으로 적재된다.
 */
public record SettleableLine(
        UUID eventId,
        SettlementSourceEventType eventType,
        UUID orderId,
        UUID orderProductId,
        UUID sellerId,
        BigDecimal lineAmount,
        LocalDateTime occurredAt
) {
}
