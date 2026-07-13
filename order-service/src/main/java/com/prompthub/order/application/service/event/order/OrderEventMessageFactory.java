package com.prompthub.order.application.service.event.order;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.infra.messaging.kafka.event.OrderCreatedPayload;
import com.prompthub.order.infra.messaging.kafka.event.OrderEventType;
import com.prompthub.order.infra.messaging.kafka.event.OrderPaidPayload;
import com.prompthub.order.infra.messaging.kafka.event.OrderRefundPayload;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

@Component
public class OrderEventMessageFactory {
	private static final String ORDER_AGGREGATE_TYPE = "ORDER";

	public EventMessage<OrderCreatedPayload> createOrderCreatedMessage(
		UUID orderId,
		OrderCreatedPayload payload
	) {
		return EventMessage.create(
			OrderEventType.ORDER_CREATED,
			LocalDateTime.now(),
			ORDER_AGGREGATE_TYPE,
			orderId,
			payload
		);
	}

	public EventMessage<OrderPaidPayload> createOrderPaidMessage(
		UUID orderId,
		OrderPaidPayload payload
	) {
		return EventMessage.create(
			OrderEventType.ORDER_PAID,
			LocalDateTime.now(),
			ORDER_AGGREGATE_TYPE,
			orderId,
			payload
		);
	}

	public EventMessage<OrderRefundPayload> createOrderRefundMessage(
		UUID orderId,
		OrderRefundPayload payload
	) {
		return EventMessage.create(
			OrderEventType.ORDER_REFUND,
			LocalDateTime.now(),
			ORDER_AGGREGATE_TYPE,
			orderId,
			payload
		);
	}
}
