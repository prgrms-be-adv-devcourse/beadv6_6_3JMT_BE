package com.prompthub.order.application.event.payment;

import java.time.LocalDateTime;
import java.util.UUID;

public record PaymentCanceledEvent(
	String eventId,
	String eventType,
	UUID paymentId,
	UUID orderId,
	UUID buyerId,
	LocalDateTime canceledAt,
	LocalDateTime occurredAt
) {
}
