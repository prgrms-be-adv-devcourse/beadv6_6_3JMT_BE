package com.prompthub.payment.infrastructure.messaging.dto;

import java.util.UUID;

public record PaymentRefundedMessage(
    UUID orderId,
    int refundAmount,
    String refundedAt
) {}
