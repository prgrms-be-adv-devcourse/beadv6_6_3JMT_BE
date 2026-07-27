package com.prompthub.order.infra.messaging.kafka.event;

import java.time.LocalDateTime;
import java.util.UUID;

public record OrderExpiredPayload(
    UUID orderId,
    UUID buyerId,
    String orderNumber,
    LocalDateTime expiredAt
) {
}
