package com.prompthub.paymentservice.infrastructure.messaging.dto;

import java.util.UUID;

public record PaymentRefundedMessage(
    String eventType,
    UUID paymentId,
    UUID orderId,
    UUID userId,
    int amount,
    String refundedAt
) {}
