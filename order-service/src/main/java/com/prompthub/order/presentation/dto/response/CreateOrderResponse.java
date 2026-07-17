package com.prompthub.order.presentation.dto.response;

import com.prompthub.order.application.dto.CreateOrderResult;
import com.prompthub.order.domain.enums.OrderProductStatus;
import com.prompthub.order.domain.enums.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Schema(description = "판매자별 주문 생성 응답")
public record CreateOrderResponse(
	@Schema(description = "전체 주문 금액", example = "45000")
	int totalAmount,
	@Schema(description = "판매자별 주문 목록")
	List<Order> orders
) {

	public static CreateOrderResponse from(CreateOrderResult result) {
		return new CreateOrderResponse(
			result.totalAmount(),
			result.orders().stream().map(Order::from).toList()
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

		private static Order from(CreateOrderResult.Order order) {
			return new Order(
				order.orderId(),
				order.orderNumber(),
				order.buyerId(),
				order.orderStatus(),
				order.orderAmount(),
				order.products().stream().map(Product::from).toList(),
				order.createdAt()
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

		private static Product from(CreateOrderResult.Product product) {
			return new Product(
				product.orderProductId(),
				product.productId(),
				product.sellerId(),
				product.productTitle(),
				product.productAmount(),
				product.orderProductStatus()
			);
		}
	}
}
