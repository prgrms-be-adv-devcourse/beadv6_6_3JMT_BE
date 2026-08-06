package com.prompthub.order.infra.messaging.kafka.event;

import java.util.UUID;

public record PaymentRefundedPayload(
	UUID orderId,
	int refundAmount,
	String refundedAt
) {
}
