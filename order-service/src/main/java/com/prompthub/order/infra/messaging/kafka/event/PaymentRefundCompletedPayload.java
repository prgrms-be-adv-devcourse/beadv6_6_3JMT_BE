package com.prompthub.order.infra.messaging.kafka.event;

import com.prompthub.order.application.dto.PaymentRefundResult;

import java.time.LocalDateTime;
import java.util.UUID;

public record PaymentRefundCompletedPayload(
	UUID refundId,
	UUID paymentId,
	UUID orderId,
	int totalRefundAmount,
	LocalDateTime refundedAt
) implements PaymentRefundResult {
}
