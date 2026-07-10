package com.prompthub.order.application.service.event;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.application.service.event.outbox.OutboxEventAppender;
import com.prompthub.order.application.service.order.OrderPolicyService;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Cart;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class PaymentApprovedProcessorTest {

    @Mock
    private ProcessedEventService processedEventService;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private CartRepository cartRepository;

    @Mock
    private OrderPaymentRepository orderPaymentRepository;

    @Mock
    private OrderEventMessageFactory orderEventMessageFactory;

    @Mock
    private OutboxEventAppender outboxEventAppender;

    @Spy
    private OrderPolicyService orderPolicyService;

    @InjectMocks
    private PaymentApprovedProcessor processor;

    @Test
    @DisplayName("승인 이벤트를 받으면 주문을 PAID로 변경하고 결제내역과 Outbox를 저장한다")
    void process_success() {
        Order order = createPendingOrderWithProducts();
        PaymentApprovedPayload payload = createPaymentApprovedPayload(order.getId(), TOTAL_AMOUNT);
        Cart cart = mock(Cart.class);
        
        UUID eventId = UUID.randomUUID();
        String eventType = "PAYMENT_APPROVED";

        given(processedEventService.isProcessed(eventId, "order-service")).willReturn(false);
        given(orderRepository.findByIdWithOrderProducts(payload.orderId())).willReturn(Optional.of(order));
        given(cartRepository.findByBuyerIdWithCartProducts(order.getBuyerId())).willReturn(Optional.of(cart));

        EventMessage<OrderPaidPayload> orderPaidMessage = new EventMessage<>(
            UUID.randomUUID(), "ORDER_PAID", payload.approvedAt(), "ORDER", order.getId(), OrderPaidPayload.from(order)
        );
        given(orderEventMessageFactory.createOrderPaidMessage(eq(order.getId()), any(OrderPaidPayload.class)))
            .willReturn(orderPaidMessage);

        processor.process(eventId, eventType, APPROVED_AT, payload);

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getPaidAt()).isEqualTo(APPROVED_AT);

        ArgumentCaptor<OrderPayment> paymentCaptor = ArgumentCaptor.forClass(OrderPayment.class);
        then(orderPaymentRepository).should().save(paymentCaptor.capture());
        OrderPayment savedPayment = paymentCaptor.getValue();
        assertThat(savedPayment.getOrderId()).isEqualTo(order.getId());
        assertThat(savedPayment.getPaymentId()).isEqualTo(PAYMENT_ID);
        assertThat(savedPayment.getApprovedAmount()).isEqualTo(TOTAL_AMOUNT);

        then(outboxEventAppender).should().append(orderPaidMessage);
        then(processedEventService).should().markProcessed(eventId, "order-service", eventType, APPROVED_AT);
        then(cart).should().removeProductsByProductIds(productIds());
    }

    @Test
    @DisplayName("이미 처리된 이벤트는 중복으로 보고 무시한다")
    void process_alreadyProcessedEvent_doNothing() {
        PaymentApprovedPayload payload = createPaymentApprovedPayload(ORDER_ID, TOTAL_AMOUNT);
        UUID eventId = UUID.randomUUID();

        given(processedEventService.isProcessed(eventId, "order-service")).willReturn(true);

        processor.process(eventId, "PAYMENT_APPROVED", APPROVED_AT, payload);

        then(orderRepository).should(never()).findByIdWithOrderProducts(any());
        then(orderPaymentRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("승인 금액이 다르면 예외가 발생한다")
    void process_amountMismatch_throwsException() {
        Order order = createPendingOrderWithProducts();
        PaymentApprovedPayload payload = createPaymentApprovedPayload(order.getId(), PRODUCT_AMOUNT_2);
        UUID eventId = UUID.randomUUID();

        given(processedEventService.isProcessed(eventId, "order-service")).willReturn(false);
        given(orderRepository.findByIdWithOrderProducts(payload.orderId())).willReturn(Optional.of(order));

        assertThatThrownBy(() -> processor.process(eventId, "PAYMENT_APPROVED", APPROVED_AT, payload))
            .isInstanceOf(OrderException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_PAYMENT_AMOUNT_MISMATCH);
    }

    @Test
    @DisplayName("FAILED 상태인 주문도 재결제를 통해 PAID로 변경될 수 있다")
    void process_fromFailedToPaid_success() {
        Order order = createPendingOrderWithProducts();
        order.markFailed(); // status FAILED
        PaymentApprovedPayload payload = createPaymentApprovedPayload(order.getId(), TOTAL_AMOUNT);
        Cart cart = mock(Cart.class);
        UUID eventId = UUID.randomUUID();
        String eventType = "PAYMENT_APPROVED";

        given(processedEventService.isProcessed(eventId, "order-service")).willReturn(false);
        given(orderRepository.findByIdWithOrderProducts(payload.orderId())).willReturn(Optional.of(order));
        given(cartRepository.findByBuyerIdWithCartProducts(order.getBuyerId())).willReturn(Optional.of(cart));

        EventMessage<OrderPaidPayload> orderPaidMessage = new EventMessage<>(
            UUID.randomUUID(), "ORDER_PAID", payload.approvedAt(), "ORDER", order.getId(), OrderPaidPayload.from(order)
        );
        given(orderEventMessageFactory.createOrderPaidMessage(eq(order.getId()), any(OrderPaidPayload.class)))
            .willReturn(orderPaidMessage);

        processor.process(eventId, eventType, APPROVED_AT, payload);

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getPaidAt()).isEqualTo(APPROVED_AT);

        ArgumentCaptor<OrderPayment> paymentCaptor = ArgumentCaptor.forClass(OrderPayment.class);
        then(orderPaymentRepository).should().save(paymentCaptor.capture());
        OrderPayment savedPayment = paymentCaptor.getValue();
        assertThat(savedPayment.getOrderId()).isEqualTo(order.getId());
        assertThat(savedPayment.getPaymentId()).isEqualTo(PAYMENT_ID);
        assertThat(savedPayment.getApprovedAmount()).isEqualTo(TOTAL_AMOUNT);

        then(outboxEventAppender).should().append(orderPaidMessage);
        then(processedEventService).should().markProcessed(eventId, "order-service", eventType, APPROVED_AT);
        then(cart).should().removeProductsByProductIds(productIds());
    }

    @Test
    @DisplayName("이미 CANCELED 상태인 주문은 PAID로 변경할 수 없으며 상태 변경 없이 처리 완료된다")
    void process_fromCanceledToPaid_ignored() {
        Order order = createPendingOrderWithProducts();
        order.markCanceled(); // status CANCELED
        PaymentApprovedPayload payload = createPaymentApprovedPayload(order.getId(), TOTAL_AMOUNT);
        UUID eventId = UUID.randomUUID();

        given(processedEventService.isProcessed(eventId, "order-service")).willReturn(false);
        given(orderRepository.findByIdWithOrderProducts(payload.orderId())).willReturn(Optional.of(order));

        processor.process(eventId, "PAYMENT_APPROVED", APPROVED_AT, payload);

        then(processedEventService).should().markProcessed(eventId, "order-service", "PAYMENT_APPROVED", APPROVED_AT);
        then(orderPaymentRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("이미 REFUNDED 상태인 주문은 PAID로 변경할 수 없으며 상태 변경 없이 처리 완료된다")
    void process_fromRefundedToPaid_ignored() {
        Order order = createPendingOrderWithProducts();
        org.springframework.test.util.ReflectionTestUtils.setField(order, "orderStatus", OrderStatus.REFUNDED);
        PaymentApprovedPayload payload = createPaymentApprovedPayload(order.getId(), TOTAL_AMOUNT);
        UUID eventId = UUID.randomUUID();

        given(processedEventService.isProcessed(eventId, "order-service")).willReturn(false);
        given(orderRepository.findByIdWithOrderProducts(payload.orderId())).willReturn(Optional.of(order));

        processor.process(eventId, "PAYMENT_APPROVED", APPROVED_AT, payload);

        then(processedEventService).should().markProcessed(eventId, "order-service", "PAYMENT_APPROVED", APPROVED_AT);
        then(orderPaymentRepository).should(never()).save(any());
    }
}
