package com.prompthub.paymentservice.infrastructure.messaging.dto;

import java.util.UUID;

public record PaymentRefundFailedMessage(
    UUID orderId,
    int refundAmount,
    String failedAt
) {}
