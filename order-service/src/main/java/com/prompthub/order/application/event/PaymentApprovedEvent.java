package com.prompthub.order.application.event;

import java.time.LocalDateTime;
import java.util.UUID;

public record PaymentApprovedEvent(
	String eventId,
	String eventType,
	UUID paymentId,
	UUID orderId,
	UUID buyerId,
	int approvedAmount,
	String paymentMethod,
	String provider,
	String pgTxId,
	LocalDateTime approvedAt,
	LocalDateTime occurredAt
) {
}
