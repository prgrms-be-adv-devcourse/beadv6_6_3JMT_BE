package com.prompthub.order.infra.messaging.kafka.event;

import java.util.UUID;

public record PaymentApprovedPayload(
	UUID paymentId,
	UUID orderId,
	UUID userId,
	int amount,
	String approvedAt
) {
}
