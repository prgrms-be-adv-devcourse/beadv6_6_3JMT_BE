package com.prompthub.order.infra.messaging.kafka.event;

import java.time.LocalDateTime;
import java.util.UUID;

public record PaymentRefundedPayload(
        UUID orderId,
        UUID paymentId,
        UUID buyerId,
        String pgTxId,
        int refundedAmount,
        LocalDateTime refundedAt
) {
}
