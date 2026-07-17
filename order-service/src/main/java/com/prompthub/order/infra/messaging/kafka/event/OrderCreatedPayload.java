package com.prompthub.order.infra.messaging.kafka.event;

import com.prompthub.order.domain.enums.OrderProductStatus;

import java.util.List;
import java.util.UUID;

public record OrderCreatedPayload(
	UUID buyerId,
	int totalAmount,
	List<Order> orders
) {

	public static OrderCreatedPayload from(
		UUID buyerId,
		List<com.prompthub.order.domain.model.Order> orders
	) {
		return new OrderCreatedPayload(
			buyerId,
			orders.stream()
				.mapToInt(com.prompthub.order.domain.model.Order::getTotalOrderAmount)
				.sum(),
			orders.stream().map(Order::from).toList()
		);
	}

	public record Order(
		UUID orderId,
		String orderNumber,
		int totalAmount,
		List<Product> products
	) {

		private static Order from(com.prompthub.order.domain.model.Order order) {
			return new Order(
				order.getId(),
				order.getOrderNumber(),
				order.getTotalOrderAmount(),
				order.getOrderProducts().stream().map(Product::from).toList()
			);
		}
	}

	public record Product(
		UUID orderProductId,
		UUID productId,
		UUID sellerId,
		String productTitle,
		int productAmount,
		OrderProductStatus status
	) {

		private static Product from(com.prompthub.order.domain.model.OrderProduct product) {
			return new Product(
				product.getId(),
				product.getProductId(),
				product.getSellerId(),
				product.getProductTitle(),
				product.getProductAmount(),
				product.getOrderStatus()
			);
		}
	}
}
