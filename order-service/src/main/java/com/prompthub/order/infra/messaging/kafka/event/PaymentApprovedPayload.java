package com.prompthub.order.infra.messaging.kafka.event;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record PaymentApprovedPayload(
	UUID paymentId,
	UUID buyerId,
	int totalAmount,
	List<PaymentApprovedOrderPayload> orders,
	LocalDateTime approvedAt
) {
}
