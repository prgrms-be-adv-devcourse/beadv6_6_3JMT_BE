package com.prompthub.order.presentation.dto.response;

import com.prompthub.order.domain.enums.OrderStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record CreateOrderResponse(
	UUID orderId,
	String orderNumber,
	UUID buyerId,
	OrderStatus orderStatus,
	List<OrderProductsResponse> products,
	int totalAmount,
	LocalDateTime createdAt,
	LocalDateTime canceledAt
) {

}
