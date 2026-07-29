package com.prompthub.order.application.dto.event.order;

import java.time.LocalDateTime;
import java.util.UUID;

public record OrderExpiredPayload(
    UUID orderId,
    UUID buyerId,
    String orderNumber,
    LocalDateTime expiredAt
) {
}
