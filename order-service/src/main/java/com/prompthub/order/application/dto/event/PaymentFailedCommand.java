package com.prompthub.order.application.dto.event;

import java.time.LocalDateTime;
import java.util.UUID;

public record PaymentFailedCommand(
	UUID paymentId,
	UUID orderId,
	UUID buyerId,
	int failedAmount,
	String failureCode,
	String failureReason,
	LocalDateTime failedAt
) {
}
