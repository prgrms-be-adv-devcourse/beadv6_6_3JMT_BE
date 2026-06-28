package com.prompthub.order.application.event.payment;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PaymentRefundedEvent(
	String eventType,
	UUID paymentId,
	UUID orderId,
	UUID userId,
	int amount,
	OffsetDateTime refundedAt
) {
}
