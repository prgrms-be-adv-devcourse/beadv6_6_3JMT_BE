package com.prompthub.order.application.event.refund;

import java.time.LocalDateTime;
import java.util.UUID;

public record OrderRefundRequestedPayload(
	UUID orderId,
	UUID orderProductId,
	UUID buyerId,
	int refundAmount,
	LocalDateTime requestedAt
) {
}
