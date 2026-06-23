package com.prompthub.order.presentation.dto.response;

import com.prompthub.order.domain.enums.PaymentStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record OrderPaymentListResponse(
	UUID orderId,
	UUID orderProductId,
	UUID paymentId,
	PaymentStatus paymentStatus,
	boolean isRefund,
	String productType,
	String title,
	int amount,
	LocalDateTime paidAt
) {
}
