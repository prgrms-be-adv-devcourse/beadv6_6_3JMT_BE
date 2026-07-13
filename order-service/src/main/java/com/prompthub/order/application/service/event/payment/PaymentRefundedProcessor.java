package com.prompthub.order.application.service.event.payment;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.application.service.event.common.ConsumedEventContext;
import com.prompthub.order.application.service.event.common.ProcessedEventService;
import com.prompthub.order.application.service.event.outbox.OutboxEventAppender;
import com.prompthub.order.application.service.event.order.OrderEventMessageFactory;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentRefundedProcessor implements PaymentEventProcessor<PaymentRefundedPayload> {

    private final ProcessedEventService processedEventService;
    private final OrderRepository orderRepository;
    private final OrderEventMessageFactory orderEventMessageFactory;
    private final OutboxEventAppender outboxEventAppender;

    public void process(ConsumedEventContext context, PaymentRefundedPayload payload) {
        processedEventService.executeOnce(context, () -> processEvent(context, payload));
    }

    private void processEvent(ConsumedEventContext context, PaymentRefundedPayload payload) {
        Order order = orderRepository.findByIdWithOrderProducts(payload.orderId())
                .orElseThrow(() -> new OrderException(ErrorCode.ORDER_NOT_FOUND));

        if (order.getOrderStatus() != OrderStatus.PAID) {
            log.warn("이미 처리된 환불이거나 금지된 상태 전이 시도입니다. 상태 변경 무시. eventId={}, eventType={}, orderId={}, currentStatus={}",
                    context.eventId(), context.eventType(), payload.orderId(), order.getOrderStatus());
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

        log.info("결제 이벤트 처리 완료. eventId={}, eventType={}, orderId={}, targetStatus={}, consumerGroup={}",
                context.eventId(), context.eventType(), payload.orderId(), OrderStatus.REFUNDED, "order-service");
    }
}
