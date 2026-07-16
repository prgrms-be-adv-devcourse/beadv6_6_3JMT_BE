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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentRefundedProcessor {

    private static final String CONSUMER_GROUP = "order-service";

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

        if (order.getOrderStatus() != OrderStatus.COMPLETED) {
            log.warn("이미 처리된 환불이거나 금지된 상태 전이 시도입니다. 상태 변경 무시. eventId={}, eventType={}, orderId={}, currentStatus={}",
                    eventId, eventType, payload.orderId(), order.getOrderStatus());
            processedEventService.markProcessed(eventId, CONSUMER_GROUP, eventType, occurredAt);
            return;
        }

        order.refund(payload.refundedAt());

        OrderRefundPayload orderRefundPayload = OrderRefundPayload.from(order, payload.refundedAt());

        EventMessage<OrderRefundPayload> orderRefundMessage =
                orderEventMessageFactory.createOrderRefundMessage(
                        order.getId(),
                        orderRefundPayload
                );

        outboxEventAppender.append(orderRefundMessage);

        processedEventService.markProcessed(
                eventId,
                CONSUMER_GROUP,
                eventType,
                occurredAt
        );

        log.info("결제 이벤트 처리 완료. eventId={}, eventType={}, orderId={}, targetStatus={}, consumerGroup={}",
                eventId, eventType, payload.orderId(), OrderStatus.ALL_REFUNDED, CONSUMER_GROUP);
    }
}
