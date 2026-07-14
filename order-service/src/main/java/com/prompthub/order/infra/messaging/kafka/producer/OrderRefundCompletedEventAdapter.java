package com.prompthub.order.infra.messaging.kafka.producer;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.application.port.OrderRefundCompletedEventPort;
import com.prompthub.order.application.service.event.outbox.OutboxEventAppender;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderRefund;
import com.prompthub.order.infra.messaging.kafka.event.OrderEventType;
import com.prompthub.order.infra.messaging.kafka.event.OrderRefundedPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class OrderRefundCompletedEventAdapter implements OrderRefundCompletedEventPort {

    private final OutboxEventAppender outboxEventAppender;

    @Override
    public void emit(OrderRefund refund, Order order, LocalDateTime refundedAt) {
        OrderRefundedPayload payload = OrderRefundedPayload.from(refund, order, refundedAt);
        EventMessage<OrderRefundedPayload> message = new EventMessage<>(
            UUID.randomUUID(),
            OrderEventType.ORDER_REFUNDED.code(),
            refundedAt,
            "ORDER",
            order.getId(),
            payload
        );
        outboxEventAppender.append(message);
    }
}
