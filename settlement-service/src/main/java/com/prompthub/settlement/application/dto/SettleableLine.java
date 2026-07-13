package com.prompthub.settlement.application.dto;

import com.prompthub.settlement.domain.model.enums.SettlementSourceLineType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * order-service 에서 gRPC 로 조회한 정산 대상 라인 1건.
 * OrderSettlementQueryPort 가 반환하며, 멱등키(orderProductId + lineType)는 적재 시 정산이 파생한다.
 */
public record SettleableLine(
        SettlementSourceLineType lineType,
        UUID orderId,
        UUID orderProductId,
        UUID sellerId,
        BigDecimal lineAmount,
        LocalDateTime occurredAt
) {
}
