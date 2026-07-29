package com.prompthub.order.application.dto.event.order;

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
