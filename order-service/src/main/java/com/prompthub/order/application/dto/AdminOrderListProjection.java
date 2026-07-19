package com.prompthub.order.application.dto;

import com.prompthub.order.domain.enums.OrderStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record AdminOrderListProjection(
	UUID orderId,
	String productTitle,
	int totalOrderCount,
	int totalOrderAmount,
	OrderStatus orderStatus,
	LocalDateTime createdAt,
	List<SellerSummary> sellers
) {
	public record SellerSummary(
		UUID sellerId,
		int productCount,
		int orderAmount
	) {
	}
}
