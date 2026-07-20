package com.prompthub.payment.infrastructure.external.toss.dto;

import java.time.OffsetDateTime;

public record TossConfirmResponse(
    String paymentKey,
    String orderId,
    String method,
    int totalAmount,
    OffsetDateTime approvedAt,
    OffsetDateTime requestedAt,
    String status
) {}
