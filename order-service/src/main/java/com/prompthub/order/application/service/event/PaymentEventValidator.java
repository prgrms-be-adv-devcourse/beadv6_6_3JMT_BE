package com.prompthub.order.application.service.event;

import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.infra.messaging.kafka.event.PaymentApprovedPayload;
import com.prompthub.order.infra.messaging.kafka.event.PaymentFailedPayload;
import com.prompthub.order.infra.messaging.kafka.event.PaymentRefundedPayload;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Component
public class PaymentEventValidator {

	private static final ZoneOffset KOREA_OFFSET = ZoneOffset.ofHours(9);

	public void validateEnvelope(UUID eventId, String eventType, LocalDateTime occurredAt) {
		if (eventId == null || eventType == null || eventType.isBlank() || occurredAt == null) {
			throw invalidInput();
		}
	}

	public void validate(PaymentFailedPayload payload) {
		if (payload == null
			|| payload.paymentId() == null
			|| payload.orderId() == null
			|| payload.userId() == null) {
			throw invalidInput();
		}
	}

	public LocalDateTime validate(PaymentApprovedPayload payload) {
		if (payload == null
			|| payload.paymentId() == null
			|| payload.orderId() == null
			|| payload.buyerId() == null
			|| payload.approvedAmount() <= 0
			|| payload.approvedAtValue() == null
			|| payload.approvedAtValue().isBlank()) {
			throw invalidInput();
		}

		try {
			return payload.approvedAt();
		} catch (DateTimeException exception) {
			throw invalidInput();
		}
	}

	public LocalDateTime validate(PaymentRefundedPayload payload) {
		if (payload == null
			|| payload.paymentId() == null
			|| payload.orderId() == null
			|| payload.userId() == null
			|| payload.orderProductId() == null
			|| payload.amount() <= 0
			|| payload.paymentStatus() == null
			|| payload.paymentStatus().isBlank()
			|| !isRefundedStatus(payload.paymentStatus())
			|| payload.refundedAt() == null
			|| payload.refundedAt().isBlank()) {
			throw invalidInput();
		}

		try {
			return OffsetDateTime.parse(payload.refundedAt())
				.withOffsetSameInstant(KOREA_OFFSET)
				.toLocalDateTime();
		} catch (DateTimeException exception) {
			throw invalidInput();
		}
	}

	private boolean isRefundedStatus(String paymentStatus) {
		return paymentStatus.equals("PARTIAL_REFUNDED") || paymentStatus.equals("ALL_REFUNDED");
	}

	private OrderException invalidInput() {
		return new OrderException(ErrorCode.INVALID_INPUT_VALUE);
	}
}
