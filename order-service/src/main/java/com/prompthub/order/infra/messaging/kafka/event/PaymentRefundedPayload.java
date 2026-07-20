package com.prompthub.order.infra.messaging.kafka.event;

import java.util.UUID;

public record PaymentRefundedPayload(
        UUID paymentId,
        UUID orderId,
        UUID userId,
        UUID orderProductId,
        int amount,
        String paymentStatus,
        String refundedAt
) {
}
