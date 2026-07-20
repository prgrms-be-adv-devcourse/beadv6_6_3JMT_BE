package com.prompthub.payment.infrastructure.messaging.dto;

import java.util.UUID;

public record PaymentFailedMessage(
    UUID orderId,
    int failedAmount,
    String failedAt
) {}
