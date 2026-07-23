package com.prompthub.order.application.event.order;

import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;

import java.util.List;
import java.util.UUID;

public record OrderProductReservationCleanupEvent(
	UUID orderId,
	UUID buyerId,
	List<UUID> productIds
) {

	public static OrderProductReservationCleanupEvent from(Order order) {
		List<UUID> productIds = order.getOrderProducts().stream()
			.map(OrderProduct::getProductId)
			.distinct()
			.sorted()
			.toList();
		return new OrderProductReservationCleanupEvent(order.getId(), order.getBuyerId(), productIds);
	}
}
