package com.prompthub.order.application.dto.event;

import java.time.LocalDateTime;
import java.util.UUID;

public record PaymentRefundFailedCommand(
	UUID orderId,
	int refundAmount,
	LocalDateTime failedAt
) {
}
