package com.prompthub.order.infra.messaging.kafka.event;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record PaymentFailedPayload(
    UUID paymentId,
    List<UUID> orderIds,
    String failureCode,
    String failureReason,
    LocalDateTime failedAt
) {
}
