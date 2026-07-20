package com.prompthub.order.application.service.event;

import com.prompthub.order.application.service.order.OrderFailureCompensationService;
import com.prompthub.order.infra.messaging.kafka.event.PaymentFailedPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentFailedProcessor {

	private final OrderFailureCompensationService compensationService;

	public void process(
		UUID eventId,
		String eventType,
		LocalDateTime occurredAt,
		PaymentFailedPayload payload
	) {
		compensationService.compensatePaymentFailure(eventId, eventType, occurredAt, payload);
	}
}
