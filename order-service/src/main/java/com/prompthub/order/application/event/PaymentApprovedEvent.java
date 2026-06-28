package com.prompthub.order.application.event;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PaymentApprovedEvent(
	String eventType,
	UUID paymentId,
	UUID orderId,
	UUID userId,
	int amount,
	OffsetDateTime approvedAt
) {
}
