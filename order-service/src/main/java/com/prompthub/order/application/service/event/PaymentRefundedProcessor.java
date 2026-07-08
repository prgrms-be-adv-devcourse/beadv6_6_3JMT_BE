package com.prompthub.order.application.service.event;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.application.service.event.outbox.OutboxEventAppender;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.infra.messaging.kafka.event.OrderRefundPayload;
import com.prompthub.order.infra.messaging.kafka.event.PaymentRefundedPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentRefundedProcessor {

    private static final String CONSUMER_GROUP = "order-service";
    private static final String ORDER_EVENTS_TOPIC = "order-events";

    private final ProcessedEventService processedEventService;
    private final OrderRepository orderRepository;
    private final OrderEventMessageFactory orderEventMessageFactory;
    private final OutboxEventAppender outboxEventAppender;

    @Transactional
    public void process(
            UUID eventId,
            String eventType,
            LocalDateTime occurredAt,
            PaymentRefundedPayload payload
    ) {
        if (processedEventService.isProcessed(eventId, CONSUMER_GROUP)) {
            return;
        }

        Order order = orderRepository.findByIdWithOrderProducts(payload.orderId())
                .orElseThrow(() -> new OrderException(ErrorCode.ORDER_NOT_FOUND));

        if (order.getOrderStatus() == OrderStatus.REFUNDED) {
            processedEventService.markProcessed(eventId, CONSUMER_GROUP, eventType, occurredAt);
            return;
        }

        if (order.getOrderStatus() != OrderStatus.PAID) {
            throw new OrderException(ErrorCode.INVALID_ORDER_STATUS_TRANSITION);
        }

        order.refund(payload.refundedAt());

        OrderRefundPayload orderRefundPayload = OrderRefundPayload.from(order, payload.refundedAt());

        EventMessage<OrderRefundPayload> orderRefundMessage =
                orderEventMessageFactory.createOrderRefundMessage(
                        order.getId(),
                        orderRefundPayload
                );

        outboxEventAppender.append(ORDER_EVENTS_TOPIC, orderRefundMessage);

        processedEventService.markProcessed(
                eventId,
                CONSUMER_GROUP,
                eventType,
                occurredAt
        );
    }
}
