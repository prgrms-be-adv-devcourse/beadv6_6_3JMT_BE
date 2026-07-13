package com.prompthub.order.application.service.event;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.application.service.event.outbox.OutboxEventAppender;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.infra.messaging.kafka.event.OrderRefundPayload;
import com.prompthub.order.infra.messaging.kafka.event.PaymentRefundedPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static com.prompthub.order.fixture.OrderFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class PaymentRefundedProcessorTest {

    @Mock
    private ProcessedEventService processedEventService;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderEventMessageFactory orderEventMessageFactory;

    @Mock
    private OutboxEventAppender outboxEventAppender;

    @InjectMocks
    private PaymentRefundedProcessor processor;

    @Test
    @DisplayName("환불 이벤트를 받으면 주문을 REFUNDED로 변경하고 Outbox를 저장한다")
    void process_success() {
        Order order = createPaidOrderWithProducts();
        PaymentRefundedPayload payload = createPaymentRefundedPayload(order.getId());
        UUID eventId = UUID.randomUUID();
        String eventType = "PAYMENT_REFUNDED";

        given(processedEventService.isProcessed(eventId, "order-service")).willReturn(false);
        given(orderRepository.findByIdWithOrderProducts(payload.orderId())).willReturn(Optional.of(order));

        EventMessage<OrderRefundPayload> orderRefundMessage = new EventMessage<>(
            UUID.randomUUID(), "ORDER_REFUND", payload.refundedAt(), "ORDER", order.getId(), OrderRefundPayload.from(order, payload.refundedAt())
        );
        given(orderEventMessageFactory.createOrderRefundMessage(eq(order.getId()), any(OrderRefundPayload.class)))
            .willReturn(orderRefundMessage);

        processor.process(eventId, eventType, REFUNDED_AT, payload);

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(order.getRefundedAt()).isEqualTo(REFUNDED_AT);
        assertThat(order.getOrderProducts())
            .extracting(OrderProduct::getOrderStatus)
            .containsOnly(OrderStatus.REFUNDED);

        then(outboxEventAppender).should().append(orderRefundMessage);
        then(processedEventService).should().markProcessed(eventId, "order-service", eventType, REFUNDED_AT);
    }

    @Test
    @DisplayName("이미 처리된 이벤트는 무시한다")
    void process_alreadyProcessedEvent_doNothing() {
        PaymentRefundedPayload payload = createPaymentRefundedPayload(ORDER_ID);
        UUID eventId = UUID.randomUUID();

        given(processedEventService.isProcessed(eventId, "order-service")).willReturn(true);

        processor.process(eventId, "PAYMENT_REFUNDED", REFUNDED_AT, payload);

        then(orderRepository).should(never()).findByIdWithOrderProducts(any());
        then(outboxEventAppender).should(never()).append(any());
    }

    @Test
    @DisplayName("이미 REFUNDED인 주문에 환불 이벤트가 오면 마킹만 하고 종료한다")
    void process_alreadyRefundedOrder_marksAndReturns() {
        Order order = createPaidOrderWithProducts();
        order.refund(REFUNDED_AT);
        PaymentRefundedPayload payload = createPaymentRefundedPayload(order.getId());
        UUID eventId = UUID.randomUUID();
        String eventType = "PAYMENT_REFUNDED";

        given(processedEventService.isProcessed(eventId, "order-service")).willReturn(false);
        given(orderRepository.findByIdWithOrderProducts(payload.orderId())).willReturn(Optional.of(order));

        processor.process(eventId, eventType, REFUNDED_AT, payload);

        then(processedEventService).should().markProcessed(eventId, "order-service", eventType, REFUNDED_AT);
        then(outboxEventAppender).should(never()).append(any());
    }

    @Test
    @DisplayName("PAID가 아닌 주문에 환불 이벤트가 들어오면 상태 변경 없이 처리 완료된다")
    void process_pendingOrder_ignored() {
        Order order = createPendingOrderWithProducts();
        PaymentRefundedPayload payload = createPaymentRefundedPayload(order.getId());
        UUID eventId = UUID.randomUUID();

        given(processedEventService.isProcessed(eventId, "order-service")).willReturn(false);
        given(orderRepository.findByIdWithOrderProducts(payload.orderId())).willReturn(Optional.of(order));

        processor.process(eventId, "PAYMENT_REFUNDED", REFUNDED_AT, payload);

        then(processedEventService).should().markProcessed(eventId, "order-service", "PAYMENT_REFUNDED", REFUNDED_AT);
        then(outboxEventAppender).should(never()).append(any());
    }
}
