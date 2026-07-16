package com.prompthub.order.application.service.event;

import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.infra.messaging.kafka.event.PaymentFailedPayload;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class PaymentEventValidator {

	public void validateEnvelope(UUID eventId, String eventType, LocalDateTime occurredAt) {
		if (eventId == null || eventType == null || eventType.isBlank() || occurredAt == null) {
			throw invalidInput();
		}
	}

	public List<UUID> validate(PaymentFailedPayload payload) {
		if (payload == null
			|| payload.paymentId() == null
			|| payload.failedAt() == null
			|| payload.failureCode() == null
			|| payload.failureCode().isBlank()
			|| payload.failureReason() == null
			|| payload.failureReason().isBlank()
			|| payload.orderIds() == null
			|| payload.orderIds().isEmpty()) {
			throw invalidInput();
		}

		Set<UUID> uniqueOrderIds = new HashSet<>();
		for (UUID orderId : payload.orderIds()) {
			if (orderId == null || !uniqueOrderIds.add(orderId)) {
				throw invalidInput();
			}
		}

		return uniqueOrderIds.stream()
			.sorted()
			.toList();
	}

	private OrderException invalidInput() {
		return new OrderException(ErrorCode.INVALID_INPUT_VALUE);
	}
}
