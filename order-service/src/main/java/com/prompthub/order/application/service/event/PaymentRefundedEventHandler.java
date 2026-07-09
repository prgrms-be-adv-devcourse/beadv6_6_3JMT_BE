package com.prompthub.order.application.service.event;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.infra.messaging.kafka.event.PaymentRefundedPayload;
import com.prompthub.order.infra.messaging.kafka.support.EventPayloadMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
@RequiredArgsConstructor
public class PaymentRefundedEventHandler {

	private final EventPayloadMapper eventPayloadMapper;
	private final PaymentRefundedProcessor paymentRefundedProcessor;

	public void handle(EventMessage<JsonNode> message) {
		PaymentRefundedPayload payload = eventPayloadMapper.convert(message, PaymentRefundedPayload.class);

		paymentRefundedProcessor.process(
			message.eventId(),
			message.eventType(),
			message.occurredAt(),
			payload
		);
	}
}
