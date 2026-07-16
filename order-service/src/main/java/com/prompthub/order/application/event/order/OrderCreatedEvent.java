package com.prompthub.order.application.event.order;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record OrderCreatedEvent(
	List<Item> orders
) {

	public static OrderCreatedEvent from(List<com.prompthub.order.domain.model.Order> orders) {
		return new OrderCreatedEvent(orders.stream()
			.map(order -> new Item(order.getId(), order.getCreatedAt()))
			.toList());
	}

	public record Item(
		UUID orderId,
		LocalDateTime createdAt
	) {
	}
}
