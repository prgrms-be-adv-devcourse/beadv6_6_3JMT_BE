package com.prompthub.order.application.event.payment;

import java.time.LocalDateTime;
import java.util.UUID;

public record PaymentFailedEvent(
	String eventId,
	String eventType,
	UUID paymentId,
	UUID orderId,
	UUID buyerId,
	String reason,
	LocalDateTime failedAt,
	LocalDateTime occurredAt
) {
}
