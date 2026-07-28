package com.prompthub.order.infra.messaging.kafka.event;

import java.time.LocalDateTime;
import java.util.UUID;

public record OrderPaymentFailedPayload(
    UUID orderId,
    UUID buyerId,
    String orderNumber,
    String failureCode,
    String failureReason,
    LocalDateTime failedAt
) {
}
