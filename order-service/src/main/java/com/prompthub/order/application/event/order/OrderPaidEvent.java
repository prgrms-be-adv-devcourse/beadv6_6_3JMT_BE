package com.prompthub.order.application.event.order;

import com.prompthub.order.domain.model.Order;

import java.util.List;
import java.util.UUID;

public record OrderPaidEvent(List<UUID> orderIds) {

	public OrderPaidEvent {
		orderIds = List.copyOf(orderIds);
	}

	public static OrderPaidEvent from(List<Order> orders) {
		return new OrderPaidEvent(orders.stream()
			.map(Order::getId)
			.sorted()
			.toList());
	}
}
