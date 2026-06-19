package com.prompthub.order.application.event;

import java.time.LocalDateTime;
import java.util.UUID;

public record PaymentApprovedEvent(
        UUID eventId,
        UUID orderId,
        UUID paymentId,
        int approvedAmount,
        LocalDateTime approvedAt
) {
}