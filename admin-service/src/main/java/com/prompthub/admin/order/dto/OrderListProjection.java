package com.prompthub.admin.order.dto;

import com.prompthub.admin.order.entity.enums.OrderStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record OrderListProjection(
	UUID orderId, String orderNumber, UUID buyerId, int totalOrderAmount,
	OrderStatus orderStatus, LocalDateTime createdAt,
	List<OrderProductSummary> orderProducts
) {
	public record OrderProductSummary(
		UUID sellerId, String productTitle, int productAmount, String orderProductStatus
	) {
	}
}
