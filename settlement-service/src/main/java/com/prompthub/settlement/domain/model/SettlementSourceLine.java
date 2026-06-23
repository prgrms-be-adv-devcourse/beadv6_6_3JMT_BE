package com.prompthub.settlement.domain.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record SettlementSourceLine(
        UUID orderProductId,
        UUID sellerId,
        BigDecimal amount,
        LocalDateTime occurredAt
) {
}
