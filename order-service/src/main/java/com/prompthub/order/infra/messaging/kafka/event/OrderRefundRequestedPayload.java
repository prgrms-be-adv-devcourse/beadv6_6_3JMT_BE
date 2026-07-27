package com.prompthub.order.infra.messaging.kafka.event;

import java.time.LocalDateTime;
import java.util.UUID;

public record OrderRefundRequestedPayload(
    UUID orderId,
    UUID buyerId,
    String orderNumber,
    UUID refundRequestId,
    int refundAmount,
    LocalDateTime requestedAt
) {
}
