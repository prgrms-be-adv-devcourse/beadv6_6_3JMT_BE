package com.prompthub.order.application.event.order;

import java.time.LocalDateTime;
import java.util.UUID;

public record OrderCreatedEvent(
	UUID orderId,
	LocalDateTime createdAt
) {

	public static OrderCreatedEvent from(com.prompthub.order.domain.model.Order order) {
		return new OrderCreatedEvent(order.getId(), order.getCreatedAt());
	}
}
