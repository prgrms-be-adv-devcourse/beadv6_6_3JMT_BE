package com.prompthub.order.application.dto.event;

import java.time.LocalDateTime;
import java.util.UUID;

public record PaymentRefundedCommand(
	UUID orderId,
	int refundAmount,
	LocalDateTime refundedAt
) {
}
