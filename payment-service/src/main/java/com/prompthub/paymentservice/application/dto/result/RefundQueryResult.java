package com.prompthub.paymentservice.application.dto.result;

import java.time.OffsetDateTime;
import java.util.UUID;

public record RefundQueryResult(
    UUID paymentId,
    UUID orderId,
    UUID userId,
    UUID orderProductId,
    int amount,
    String paymentStatus,
    String refundStatus,
    OffsetDateTime refundedAt
) {}
