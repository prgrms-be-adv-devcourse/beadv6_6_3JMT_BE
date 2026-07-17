package com.prompthub.order.application.dto;

import com.prompthub.order.domain.enums.OrderProductStatus;
import com.prompthub.order.domain.enums.OrderStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record CreateOrderResult(
	int totalAmount,
	List<Order> orders
) {

	public static CreateOrderResult from(List<com.prompthub.order.domain.model.Order> orders) {
		return new CreateOrderResult(
			orders.stream()
				.mapToInt(com.prompthub.order.domain.model.Order::getTotalOrderAmount)
				.sum(),
			orders.stream().map(Order::from).toList()
		);
	}

	public record Order(
		UUID orderId,
		String orderNumber,
		UUID buyerId,
		OrderStatus orderStatus,
		int orderAmount,
		List<Product> products,
		LocalDateTime createdAt
	) {

		private static Order from(com.prompthub.order.domain.model.Order order) {
			return new Order(
				order.getId(),
				order.getOrderNumber(),
				order.getBuyerId(),
				order.getOrderStatus(),
				order.getTotalOrderAmount(),
				order.getOrderProducts().stream().map(Product::from).toList(),
				order.getCreatedAt()
			);
		}
	}

	public record Product(
		UUID orderProductId,
		UUID productId,
		UUID sellerId,
		String productTitle,
		int productAmount,
		OrderProductStatus orderProductStatus
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
