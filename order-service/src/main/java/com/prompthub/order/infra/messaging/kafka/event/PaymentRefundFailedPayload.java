package com.prompthub.order.infra.messaging.kafka.event;

import java.util.UUID;

public record PaymentRefundFailedPayload(
	UUID orderId,
	int refundAmount,
	String failedAt
) {
}
