package com.prompthub.order.application.service.event.order;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.infra.messaging.kafka.event.OrderCreatedPayload;
import com.prompthub.order.infra.messaging.kafka.event.OrderEventType;
import com.prompthub.order.infra.messaging.kafka.event.OrderPaidPayload;
import com.prompthub.order.infra.messaging.kafka.event.OrderRefundPayload;
import com.prompthub.order.infra.messaging.kafka.event.RefundRequestedPayload;
import com.prompthub.order.domain.model.OrderRefund;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

@Component
public class OrderEventMessageFactory {
	private static final String ORDER_AGGREGATE_TYPE = "ORDER";
	private static final String REFUND_REQUEST_AGGREGATE_TYPE = "REFUND_REQUEST";

	public EventMessage<RefundRequestedPayload> createRefundRequestedMessage(OrderRefund refund) {
		return EventMessage.create(
			OrderEventType.REFUND_REQUESTED,
			refund.getRequestedAt(),
			"ORDER_REFUND",
			refund.getId(),
			RefundRequestedPayload.from(refund)
		);
	}

    public EventMessage<RefundRequestedPayload> createRefundRequestedMessage(
            UUID refundRequestId,
            RefundRequestedPayload payload
    ) {
        return EventMessage.create(
                OrderEventType.REFUND_REQUESTED,
                payload.requestedAt(),
                REFUND_REQUEST_AGGREGATE_TYPE,
                refundRequestId,
                payload
        );
    }

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
