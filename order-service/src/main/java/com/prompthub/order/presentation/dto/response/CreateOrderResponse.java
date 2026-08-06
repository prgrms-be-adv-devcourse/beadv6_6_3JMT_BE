package com.prompthub.order.presentation.dto.response;

import com.prompthub.order.application.dto.CreateOrderResult;
import com.prompthub.order.domain.enums.OrderProductStatus;
import com.prompthub.order.domain.enums.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Schema(description = "주문 생성 응답")
public record CreateOrderResponse(
	@Schema(description = "전체 주문 금액", example = "45000")
	int totalAmount,
	@Schema(description = "생성된 주문")
	Order order
) {

	public static CreateOrderResponse from(CreateOrderResult result) {
		return new CreateOrderResponse(
			result.totalAmount(),
			Order.from(result.order())
		);
	}

	public record Order(
		@Schema(description = "주문 ID", example = "9f1c2a7e-4b8d-4e2a-9c11-2d3e4f5a1111")
		UUID orderId,
		@Schema(description = "주문 번호", example = "ORD-20260618-000001")
		String orderNumber,
		@Schema(description = "구매자 ID", example = "7c2f6e91-2c1b-4a3b-9f99-3f527f7d1234")
		UUID buyerId,
		@Schema(description = "주문 상태. CREATED, COMPLETED, FAILED, REFUND_REQUESTED, PARTIAL_REFUNDED, ALL_REFUNDED", example = "CREATED")
		OrderStatus orderStatus,
		@Schema(description = "주문 금액. 원 단위 정수", example = "45000")
		int orderAmount,
		@Schema(description = "주문 상품 목록")
		List<Product> products,
		@Schema(description = "주문 생성 일시. yyyy-MM-dd'T'HH:mm:ss 형식", example = "2026-06-18T14:30:00")
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
		@Schema(description = "주문 상품 ID", example = "72d95cb0-1835-49bf-8f08-2e0f1c4e4aaa")
		UUID orderProductId,
		@Schema(description = "상품 ID", example = "a1b55b60-5e84-4f3f-b4f1-6c10e1a22222")
		UUID productId,
		@Schema(description = "주문 상품별 판매자 ID", example = "8f2c6e91-2c1b-4a3b-9f99-3f527f7d5678")
		UUID sellerId,
		@Schema(description = "상품 서비스에서 조회한 주문 시점 제목 스냅샷", example = "면접 준비 프롬프트")
		String productTitle,
		@Schema(description = "주문 시점 상품 금액 스냅샷. 원 단위 정수", example = "15000")
		int productAmount,
		@Schema(description = "주문 상품 상태. PENDING, PAID, FAILED, REFUND_REQUESTED, REFUNDED", example = "PENDING")
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
