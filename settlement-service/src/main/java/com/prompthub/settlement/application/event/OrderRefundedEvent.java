package com.prompthub.settlement.application.event;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record OrderRefundedEvent(
        UUID orderId,
        UUID buyerId,
        int totalRefundAmount,
        LocalDateTime refundedAt,
        List<OrderRefundedProduct> products
) {
}
