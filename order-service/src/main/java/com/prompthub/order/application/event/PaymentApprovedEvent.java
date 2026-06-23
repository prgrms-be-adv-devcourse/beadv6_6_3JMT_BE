package com.prompthub.order.application.event;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PaymentApprovedEvent(
	UUID eventId,
	UUID orderId,
	UUID paymentId,
	String pgTxId,
	String paymentMethod,
	String provider,
	int approvedAmount,
	OffsetDateTime approvedAt
) {
}
