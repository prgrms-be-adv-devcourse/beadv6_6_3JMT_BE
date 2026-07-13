package com.prompthub.paymentservice.infrastructure.messaging.dto;

import java.util.UUID;

public record PaymentRefundFailedMessage(
    UUID paymentId,
    UUID orderId,
    UUID userId,
    UUID orderProductId,
    int refundAmount,
    String failureReason,
    String failedAt
) {}
