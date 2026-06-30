package com.prompthub.order.application.dto;

import com.prompthub.order.domain.enums.OrderStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record OrderPaymentListProjection(
	UUID orderId,
	UUID paymentId,
	OrderStatus orderStatus,
	boolean isRefundable,
	String productType,
	String title,
	int amount,
	LocalDateTime paidAt,
	LocalDateTime approvedAt
) {
}
