package com.prompthub.order.application.service.event;

import com.prompthub.order.application.dto.event.PaymentApprovedCommand;
import com.prompthub.order.application.dto.event.PaymentFailedCommand;
import com.prompthub.order.application.dto.event.PaymentRefundFailedCommand;
import com.prompthub.order.application.dto.event.PaymentRefundedCommand;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

@Component
public class PaymentEventValidator {

	public void validateEnvelope(UUID eventId, String eventType, LocalDateTime occurredAt) {
		if (eventId == null || eventType == null || eventType.isBlank() || occurredAt == null) {
			throw invalidInput();
		}
	}

	public void validate(PaymentFailedCommand payload) {
		if (payload == null
			|| payload.orderId() == null
			|| payload.failedAmount() < 0
			|| payload.failedAt() == null) {
			throw invalidInput();
		}
	}

	public LocalDateTime validate(PaymentApprovedCommand payload) {
		if (payload == null
			|| payload.orderId() == null
			|| payload.approvedAt() == null) {
			throw invalidInput();
		}
		return payload.approvedAt();
	}

	public LocalDateTime validate(PaymentRefundedCommand payload) {
		if (payload == null
			|| payload.orderId() == null
			|| payload.refundAmount() <= 0
			|| payload.refundedAt() == null) {
			throw invalidInput();
		}
		return payload.refundedAt();
	}

	public LocalDateTime validate(PaymentRefundFailedCommand payload) {
		if (payload == null
			|| payload.orderId() == null
			|| payload.refundAmount() <= 0
			|| payload.failedAt() == null) {
			throw invalidInput();
		}
		return payload.failedAt();
	}

	private OrderException invalidInput() {
		return new OrderException(ErrorCode.INVALID_INPUT_VALUE);
	}
}
