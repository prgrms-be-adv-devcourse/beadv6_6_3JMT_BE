package com.prompthub.order.application.event;

import java.time.LocalDateTime;
import java.util.UUID;

public record PaymentRefundedEvent(
	UUID paymentId,
	UUID orderId,
	UUID buyerId,
	int refundedAmount,
	LocalDateTime refundedAt
) {
}
