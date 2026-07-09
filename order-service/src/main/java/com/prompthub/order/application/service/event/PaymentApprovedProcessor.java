package com.prompthub.order.application.service.event;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.application.service.event.outbox.OutboxEventAppender;
import com.prompthub.order.application.service.order.OrderExpirationStore;
import com.prompthub.order.application.service.order.OrderPolicyService;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderPayment;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.repository.CartRepository;
import com.prompthub.order.domain.repository.OrderPaymentRepository;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.infra.messaging.kafka.event.OrderPaidPayload;
import com.prompthub.order.infra.messaging.kafka.event.PaymentApprovedPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentApprovedProcessor {

    private static final String CONSUMER_GROUP = "order-service";
    private static final String ORDER_EVENTS_TOPIC = "order-events";

    private final ProcessedEventService processedEventService;
    private final OrderRepository orderRepository;
    private final CartRepository cartRepository;
    private final OrderPaymentRepository orderPaymentRepository;
    private final OrderEventMessageFactory orderEventMessageFactory;
    private final OutboxEventAppender outboxEventAppender;
    private final OrderPolicyService orderPolicyService;
    private final OrderExpirationStore orderExpirationStore;

    @Transactional
    public void process(
            UUID eventId,
            String eventType,
            LocalDateTime occurredAt,
            PaymentApprovedPayload payload
    ) {
        if (processedEventService.isProcessed(eventId, CONSUMER_GROUP)) {
            return;
        }

        Order order = orderRepository.findByIdWithOrderProducts(payload.orderId())
                .orElseThrow(() -> new OrderException(ErrorCode.ORDER_NOT_FOUND));

        if (order.getOrderStatus() != OrderStatus.PENDING && order.getOrderStatus() != OrderStatus.FAILED) {
            log.warn("이미 처리된 주문이거나 금지된 상태 전이 시도입니다. 상태 변경 무시. eventId={}, eventType={}, orderId={}, currentStatus={}",
                    eventId, eventType, payload.orderId(), order.getOrderStatus());
            processedEventService.markProcessed(eventId, CONSUMER_GROUP, eventType, occurredAt);
            return;
        }

        orderPolicyService.validatePaymentApproval(order, payload);
        order.markPaid(payload.approvedAt());

        orderPaymentRepository.save(OrderPayment.create(
                order.getId(),
                payload.paymentId(),
                payload.buyerId(),
                payload.approvedAmount(),
                payload.approvedAt()
        ));

        removeExpirationQuietly(order.getId());

        OrderPaidPayload orderPaidPayload = OrderPaidPayload.from(order);

        EventMessage<OrderPaidPayload> orderPaidMessage =
                orderEventMessageFactory.createOrderPaidMessage(
                        order.getId(),
                        orderPaidPayload
                );

        outboxEventAppender.append(ORDER_EVENTS_TOPIC, orderPaidMessage);

        processedEventService.markProcessed(
                eventId,
                CONSUMER_GROUP,
                eventType,
                occurredAt
        );

        var orderedProductIds = order.getOrderProducts().stream()
                .map(OrderProduct::getProductId)
                .toList();

        cartRepository.findByBuyerIdWithCartProducts(order.getBuyerId())
                .ifPresent(cart -> cart.removeProductsByProductIds(orderedProductIds));

        log.info("결제 이벤트 처리 완료. eventId={}, eventType={}, orderId={}, targetStatus={}, consumerGroup={}",
                eventId, eventType, payload.orderId(), OrderStatus.PAID, CONSUMER_GROUP);
    }

    private void removeExpirationQuietly(UUID orderId) {
        try {
            orderExpirationStore.removeExpiration(orderId);
            orderExpirationStore.clearRetryCount(orderId);
        } catch (Exception exception) {
            log.warn("결제 완료 주문의 Redis 만료 대상 제거에 실패했습니다. orderId={}", orderId, exception);
        }
    }
}
