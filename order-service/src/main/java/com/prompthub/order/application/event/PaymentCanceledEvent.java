package com.prompthub.order.application.event;

import java.time.LocalDateTime;
import java.util.UUID;

public record PaymentCanceledEvent(
	UUID paymentId,
	UUID orderId,
	UUID buyerId,
	LocalDateTime canceledAt
) {
}
