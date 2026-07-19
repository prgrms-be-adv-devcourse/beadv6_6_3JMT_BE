package com.prompthub.order.infra.messaging.kafka.event;

import java.util.UUID;

public record PaymentFailedPayload(
	UUID paymentId,
	UUID orderId,
	UUID userId
) {
}
