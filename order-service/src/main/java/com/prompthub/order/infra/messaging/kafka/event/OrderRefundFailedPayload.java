package com.prompthub.order.infra.messaging.kafka.event;

import java.time.LocalDateTime;
import java.util.UUID;

public record OrderRefundFailedPayload(
    UUID orderId,
    UUID buyerId,
    String orderNumber,
    int refundAmount,
    LocalDateTime failedAt
) {
}
