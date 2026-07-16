package com.prompthub.order.application.dto;

import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.enums.OrderProductStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record OrderListProjection(
	UUID orderId,
	UUID orderProductId,
	UUID productId,
	OrderStatus orderStatus,
	OrderProductStatus orderProductStatus,
	boolean downloaded,
	String productType,
	String title,
	String model,
	Double rating,
	// String thumbnailUrl,
	LocalDateTime paidAt,
	LocalDateTime createdAt
) {

}
