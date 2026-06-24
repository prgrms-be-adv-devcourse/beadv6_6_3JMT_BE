package com.prompthub.order.application.service.outbox;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record OrderPaidOutboxPayload(
	UUID orderId,
	UUID buyerId,
	UUID paymentId,
	int totalAmount,
	LocalDateTime paidAt,
	List<UUID> orderProductIds
) {
}
