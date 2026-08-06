package com.prompthub.order.application.dto.event.order;

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
