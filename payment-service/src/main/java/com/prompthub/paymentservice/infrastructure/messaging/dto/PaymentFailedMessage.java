package com.prompthub.paymentservice.infrastructure.messaging.dto;

import java.util.UUID;

public record PaymentFailedMessage(
    String eventType,
    UUID paymentId,
    UUID orderId,
    UUID userId
) {}
