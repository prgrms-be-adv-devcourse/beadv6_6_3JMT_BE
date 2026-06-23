package com.prompthub.order.application.dto;

import com.prompthub.order.domain.enums.OrderStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record OrderPaymentListProjection(
	UUID orderId,
	UUID orderProductId,
	UUID paymentId,
	OrderStatus orderStatus,
	OrderStatus orderProductStatus,
	String productType,
	String title,
	int amount,
	LocalDateTime paidAt,
	LocalDateTime approvedAt
) {
}
