package com.prompthub.order.infra.messaging.kafka.consumer.payment;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.application.dto.event.PaymentRefundFailedCommand;
import com.prompthub.order.application.dto.event.PaymentRefundedCommand;
import com.prompthub.order.application.service.event.PaymentRefundedProcessor;
import com.prompthub.order.infra.messaging.kafka.event.PaymentEventTimeParser;
import com.prompthub.order.infra.messaging.kafka.event.PaymentRefundFailedPayload;
import com.prompthub.order.infra.messaging.kafka.event.PaymentRefundedPayload;
import com.prompthub.order.infra.messaging.kafka.support.EventPayloadMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;

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
			new PaymentRefundedCommand(
				payload.orderId(),
				payload.refundAmount(),
				parseTimestamp(payload.refundedAt())
			)
		);
	}

	public void handleFailed(EventMessage<JsonNode> message) {
		PaymentRefundFailedPayload payload = eventPayloadMapper.convert(message, PaymentRefundFailedPayload.class);
		paymentRefundedProcessor.processFailed(
			message.eventId(),
			message.eventType(),
			message.occurredAt(),
			new PaymentRefundFailedCommand(
				payload.orderId(),
				payload.refundAmount(),
				parseTimestamp(payload.failedAt())
			)
		);
	}

	private LocalDateTime parseTimestamp(String value) {
		return PaymentEventTimeParser.parseOrNull(value);
	}
}
