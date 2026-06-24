package com.prompthub.settlement.application.dto;

import com.prompthub.settlement.domain.model.enums.SettlementSourceEventType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record RecordSettlementSourceCommand(
        UUID eventId,
        SettlementSourceEventType eventType,
        UUID orderId,
        UUID orderProductId,
        UUID sellerId,
        BigDecimal amount,
        LocalDateTime occurredAt
) {
}
