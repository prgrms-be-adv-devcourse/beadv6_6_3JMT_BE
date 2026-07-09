package com.prompthub.order.infra.messaging.kafka.event;

import java.time.LocalDateTime;
import java.util.UUID;

public record PaymentFailedPayload(
        UUID orderId,
        UUID paymentId,
        UUID buyerId,
        String failureReason,
        LocalDateTime failedAt
) {
}
