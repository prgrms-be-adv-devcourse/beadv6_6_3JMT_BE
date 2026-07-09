package com.prompthub.order.infra.messaging.kafka.event;

import java.time.LocalDateTime;
import java.util.UUID;

public record PaymentApprovedPayload(
        UUID orderId,
        UUID paymentId,
        UUID buyerId,
        String pgTxId,
        String paymentMethod,
        String provider,
        int approvedAmount,
        LocalDateTime approvedAt
) {
}
