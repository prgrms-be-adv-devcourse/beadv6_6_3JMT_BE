package com.prompthub.order.presentation.dto.response;

import com.prompthub.order.domain.enums.OrderStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record OrderListResponse(
	UUID orderId,
	UUID orderProductId,
	OrderStatus orderStatus,
	boolean isRefund,
	String productType,
	String title,
	String model,
	Double rating,
	// String thumbnailUrl,
	LocalDateTime paidAt,
	LocalDateTime createdAt
) {
}
