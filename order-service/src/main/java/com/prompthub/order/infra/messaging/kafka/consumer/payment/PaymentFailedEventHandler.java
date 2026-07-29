package com.prompthub.order.infra.messaging.kafka.consumer.payment;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.application.dto.event.PaymentFailedCommand;
import com.prompthub.order.application.service.event.PaymentFailedProcessor;
import com.prompthub.order.infra.messaging.kafka.event.PaymentFailedPayload;
import com.prompthub.order.infra.messaging.kafka.support.EventPayloadMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
@RequiredArgsConstructor
public class PaymentFailedEventHandler {

	private final EventPayloadMapper eventPayloadMapper;
	private final PaymentFailedProcessor paymentFailedProcessor;

	public void handle(EventMessage<JsonNode> message) {
		PaymentFailedPayload payload = eventPayloadMapper.convert(message, PaymentFailedPayload.class);
		paymentFailedProcessor.process(
			message.eventId(),
			message.eventType(),
			message.occurredAt(),
			new PaymentFailedCommand(
				payload.paymentId(),
				payload.orderId(),
				payload.buyerId(),
				payload.failedAmount(),
				payload.failureCode(),
				payload.failureReason(),
				payload.failedAtOr(message.occurredAt())
			)
		);
	}
}
