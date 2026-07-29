package com.prompthub.order.infra.messaging.kafka.consumer.payment;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.application.dto.event.PaymentApprovedCommand;
import com.prompthub.order.application.service.event.PaymentApprovedProcessor;
import com.prompthub.order.infra.messaging.kafka.event.PaymentApprovedPayload;
import com.prompthub.order.infra.messaging.kafka.support.EventPayloadMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
@RequiredArgsConstructor
public class PaymentApprovedEventHandler {

	private final EventPayloadMapper eventPayloadMapper;
	private final PaymentApprovedProcessor paymentApprovedProcessor;

	public void handle(EventMessage<JsonNode> message) {
		PaymentApprovedPayload payload = eventPayloadMapper.convert(message, PaymentApprovedPayload.class);
		paymentApprovedProcessor.process(
			message.eventId(),
			message.eventType(),
			message.occurredAt(),
			new PaymentApprovedCommand(
				payload.orderId(),
				payload.approvedAmount() == null ? -1 : payload.approvedAmount(),
				payload.approvedAt()
			)
		);
	}
}
