package com.prompthub.order.application.dto;

import com.prompthub.order.domain.enums.OrderStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record AdminOrderListProjection(
	UUID orderId,
	UUID sellerId,
	String sellerNickname,
	String productTitle,
	int totalOrderCount,
	int totalOrderAmount,
	OrderStatus orderStatus,
	LocalDateTime createdAt
) {
}
