package com.prompthub.order.application.service.event;

import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.infra.messaging.kafka.event.PaymentApprovedPayload;
import com.prompthub.order.infra.messaging.kafka.event.PaymentFailedPayload;
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
			|| payload.userId() == null
			|| payload.amount() <= 0
			|| payload.approvedAt() == null
			|| payload.approvedAt().isBlank()) {
			throw invalidInput();
		}

		try {
			return OffsetDateTime.parse(payload.approvedAt())
				.withOffsetSameInstant(KOREA_OFFSET)
				.toLocalDateTime();
		} catch (DateTimeException exception) {
			throw invalidInput();
		}
	}

	private OrderException invalidInput() {
		return new OrderException(ErrorCode.INVALID_INPUT_VALUE);
	}
}
