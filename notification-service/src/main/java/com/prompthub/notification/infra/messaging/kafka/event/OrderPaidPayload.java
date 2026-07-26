package com.prompthub.notification.infra.messaging.kafka.event;

import java.time.LocalDateTime;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderPaidPayload(
    UUID orderId,
    UUID buyerId,
    int totalOrderAmount,
    int totalProductCount,
    LocalDateTime paidAt
) {
}
