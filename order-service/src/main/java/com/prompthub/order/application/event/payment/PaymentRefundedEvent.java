package com.prompthub.order.application.event.payment;

import java.time.LocalDateTime;
import java.util.UUID;

public record PaymentRefundedEvent(
	String eventId,
	String eventType,
	UUID paymentId,
	UUID orderId,
	UUID buyerId,
	int refundedAmount,
	LocalDateTime refundedAt,
	LocalDateTime occurredAt
) {
}
