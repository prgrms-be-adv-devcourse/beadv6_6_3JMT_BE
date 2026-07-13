package com.prompthub.order.infra.messaging.kafka.router;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.application.service.event.common.ConsumedEventContext;
import com.prompthub.order.application.service.event.payment.PaymentApprovedProcessor;
import com.prompthub.order.application.service.event.payment.PaymentCanceledProcessor;
import com.prompthub.order.application.service.event.payment.PaymentEventProcessor;
import com.prompthub.order.application.service.event.payment.PaymentFailedProcessor;
import com.prompthub.order.application.service.event.payment.PaymentRefundCompletedProcessor;
import com.prompthub.order.application.service.event.payment.PaymentRefundFailedProcessor;
import com.prompthub.order.application.service.event.payment.PaymentRefundedProcessor;
import com.prompthub.order.infra.messaging.kafka.event.PaymentApprovedPayload;
import com.prompthub.order.infra.messaging.kafka.event.PaymentCanceledPayload;
import com.prompthub.order.infra.messaging.kafka.event.PaymentEventType;
import com.prompthub.order.infra.messaging.kafka.event.PaymentFailedPayload;
import com.prompthub.order.infra.messaging.kafka.event.PaymentRefundCompletedPayload;
import com.prompthub.order.infra.messaging.kafka.event.PaymentRefundFailedPayload;
import com.prompthub.order.infra.messaging.kafka.event.PaymentRefundedPayload;
import com.prompthub.order.infra.messaging.kafka.support.EventPayloadMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Map;

@Component
@Slf4j
public class PaymentEventRouter {

	private final EventPayloadMapper eventPayloadMapper;
	private final Map<PaymentEventType, PaymentRoute<?>> routes;

	public PaymentEventRouter(
		EventPayloadMapper eventPayloadMapper,
		PaymentApprovedProcessor approvedProcessor,
		PaymentRefundedProcessor refundedProcessor,
		PaymentFailedProcessor failedProcessor,
		PaymentCanceledProcessor canceledProcessor,
		PaymentRefundCompletedProcessor refundCompletedProcessor,
		PaymentRefundFailedProcessor refundFailedProcessor
	) {
		this.eventPayloadMapper = eventPayloadMapper;
		this.routes = Map.of(
			PaymentEventType.PAYMENT_APPROVED,
			new PaymentRoute<>(PaymentApprovedPayload.class, approvedProcessor),
			PaymentEventType.PAYMENT_REFUNDED,
			new PaymentRoute<>(PaymentRefundedPayload.class, refundedProcessor),
			PaymentEventType.PAYMENT_FAILED,
			new PaymentRoute<>(PaymentFailedPayload.class, failedProcessor),
			PaymentEventType.PAYMENT_CANCELED,
			new PaymentRoute<>(PaymentCanceledPayload.class, canceledProcessor),
			PaymentEventType.PAYMENT_REFUND_COMPLETED,
			new PaymentRoute<>(PaymentRefundCompletedPayload.class, refundCompletedProcessor),
			PaymentEventType.PAYMENT_REFUND_FAILED,
			new PaymentRoute<>(PaymentRefundFailedPayload.class, refundFailedProcessor)
		);
	}

	public void route(EventMessage<JsonNode> message) {
		PaymentEventType eventType = PaymentEventType.from(message.eventType());
		if (eventType == null) {
			log.warn("Unsupported payment event. eventId={}, eventType={}",
				message.eventId(), message.eventType());
			return;
		}

		dispatch(message, routes.get(eventType));
	}

	private <P> void dispatch(EventMessage<JsonNode> message, PaymentRoute<P> route) {
		P payload = eventPayloadMapper.convert(message, route.payloadType());
		route.processor().process(ConsumedEventContext.from(message), payload);
	}

	private record PaymentRoute<P>(
		Class<P> payloadType,
		PaymentEventProcessor<P> processor
	) {
	}
}
