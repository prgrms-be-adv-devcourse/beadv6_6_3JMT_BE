package com.prompthub.order.application.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record OrderForPaymentResult(
	UUID orderId,
	UUID buyerId,
	int totalAmount,
	LocalDateTime createdAt
) {
}
