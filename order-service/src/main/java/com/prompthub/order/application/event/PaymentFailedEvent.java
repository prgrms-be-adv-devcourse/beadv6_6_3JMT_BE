package com.prompthub.order.application.event;

import java.time.LocalDateTime;
import java.util.UUID;

public record PaymentFailedEvent(
	UUID paymentId,
	UUID orderId,
	UUID buyerId,
	String reason,
	LocalDateTime failedAt
) {
}
