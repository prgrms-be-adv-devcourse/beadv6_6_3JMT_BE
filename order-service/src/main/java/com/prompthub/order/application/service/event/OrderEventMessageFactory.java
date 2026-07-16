package com.prompthub.order.application.service.event;

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

	public EventMessage<OrderCreatedPayload> createOrderCreatedMessage(OrderCreatedPayload payload) {
		UUID orderGroupId = UUID.randomUUID();
        return new EventMessage<>(
			orderGroupId,
			OrderEventType.ORDER_CREATED.code(),
			LocalDateTime.now(),
			"ORDER_GROUP",
			orderGroupId,
			payload
        );
    }

    public EventMessage<OrderPaidPayload> createOrderPaidMessage(
            UUID orderId,
            OrderPaidPayload payload
    ) {
        return new EventMessage<>(
                UUID.randomUUID(),
                OrderEventType.ORDER_PAID.code(),
                LocalDateTime.now(),
                "ORDER",
                orderId,
                payload
        );
    }

    public EventMessage<OrderRefundPayload> createOrderRefundMessage(
            UUID orderId,
            OrderRefundPayload payload
    ) {
        return new EventMessage<>(
                UUID.randomUUID(),
                OrderEventType.ORDER_REFUND.code(),
                LocalDateTime.now(),
                "ORDER",
                orderId,
                payload
        );
    }
}
