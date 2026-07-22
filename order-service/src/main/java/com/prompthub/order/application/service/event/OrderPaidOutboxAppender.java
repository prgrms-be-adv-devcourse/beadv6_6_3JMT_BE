package com.prompthub.order.application.service.event;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.application.service.event.outbox.OutboxEventAppender;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.infra.messaging.kafka.event.OrderPaidPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrderPaidOutboxAppender {

	private final OrderEventMessageFactory orderEventMessageFactory;
	private final OutboxEventAppender outboxEventAppender;

	public void append(Order order) {
		EventMessage<OrderPaidPayload> message = orderEventMessageFactory.createOrderPaidMessage(
			order.getId(),
			OrderPaidPayload.from(order)
		);
		outboxEventAppender.append(message);
	}
}
