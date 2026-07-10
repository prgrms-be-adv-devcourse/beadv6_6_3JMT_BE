package com.prompthub.paymentservice.infrastructure.messaging.dto;

import java.util.UUID;

public record PaymentRefundedMessage(
    UUID paymentId,
    UUID orderId,
    UUID userId,
    int amount,
    String refundedAt
) {}
