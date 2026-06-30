package com.prompthub.order.application.event.order;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record OrderRefundEvent(
	UUID orderId,
	UUID paymentId,
	UUID buyerId,
	int totalRefundAmount,
	int totalProductCount,
	LocalDateTime refundedAt,
	List<OrderRefundProduct> products
) {
}
