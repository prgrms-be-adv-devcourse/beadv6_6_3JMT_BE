package com.prompthub.order.application.dto;

import com.prompthub.order.domain.enums.OrderStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record OrderListProjection(
	UUID orderId,
	UUID orderProductId,
	OrderStatus orderStatus,
	OrderStatus orderProductStatus,
	boolean download,
	String productType,
	String title,
	String model,
	Double rating,
	// String thumbnailUrl,
	LocalDateTime paidAt,
	LocalDateTime createdAt
) {

}
