package com.prompthub.order.application.dto;

import com.prompthub.order.domain.enums.SettlementLineType;

import java.time.LocalDateTime;
import java.util.UUID;

public record SettleableLineResult(
    SettlementLineType lineType,
    UUID orderId,
    UUID orderProductId,
    UUID sellerId,
    long lineAmount,
    LocalDateTime occurredAt
) {
}
