package com.prompthub.order.application.service.event.payment;

import com.prompthub.order.application.service.event.common.ConsumedEventContext;
import com.prompthub.order.application.service.event.common.ProcessedEventService;

import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.infra.messaging.kafka.event.PaymentCanceledPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentCanceledProcessor implements PaymentEventProcessor<PaymentCanceledPayload> {

    private final ProcessedEventService processedEventService;
    private final OrderRepository orderRepository;

    public void process(ConsumedEventContext context, PaymentCanceledPayload payload) {
        processedEventService.executeOnce(context, () -> processEvent(context, payload));
    }

    private void processEvent(ConsumedEventContext context, PaymentCanceledPayload payload) {
        Order order = orderRepository.findByIdWithOrderProducts(payload.orderId())
                .orElseThrow(() -> new OrderException(ErrorCode.ORDER_NOT_FOUND));

        if (order.getOrderStatus() != OrderStatus.PENDING) {
            log.warn("이미 처리된 주문이거나 금지된 상태 전이 시도입니다. 상태 변경 무시. eventId={}, eventType={}, orderId={}, currentStatus={}",
                    context.eventId(), context.eventType(), payload.orderId(), order.getOrderStatus());
            return;
        }

        order.markCanceled(payload.canceledAt() != null ? payload.canceledAt() : context.occurredAt());

        log.info("결제 이벤트 처리 완료. eventId={}, eventType={}, orderId={}, targetStatus={}, consumerGroup={}",
                context.eventId(), context.eventType(), payload.orderId(), OrderStatus.CANCELED, "order-service");
    }
}
