package com.prompthub.order.presentation.dto.response;

import com.prompthub.order.domain.enums.OrderStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record OrderDetailResponse(
	UUID orderId,
	String orderNumber,
	UUID buyerId,
	OrderStatus orderStatus,
	List<OrderDetailProductResponse> products,
	int totalAmount,
	int totalProductCount,
	LocalDateTime paidAt,
	LocalDateTime canceledAt,
	LocalDateTime refundedAt,
	LocalDateTime createdAt,
	boolean hasDownloadProduct
) {
}
