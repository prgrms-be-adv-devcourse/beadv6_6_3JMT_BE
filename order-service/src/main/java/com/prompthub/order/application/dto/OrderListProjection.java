package com.prompthub.order.application.dto;

import com.prompthub.order.domain.enums.OrderStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record OrderListProjection(
	UUID orderId,
	String orderNumber,
	OrderStatus orderStatus,
	int totalAmount,
	LocalDateTime paidAt,
	LocalDateTime createdAt
) {

}
