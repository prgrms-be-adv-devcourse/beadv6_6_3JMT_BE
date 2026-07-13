package com.prompthub.order.application.service.event.payment;

import com.prompthub.order.application.service.event.common.ConsumedEventContext;
import com.prompthub.order.application.service.event.common.ProcessedEventService;

import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.fixture.OrderFixture;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.infra.messaging.kafka.event.PaymentFailedPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PaymentFailedProcessorTest {

    private ProcessedEventService processedEventService;
    private OrderRepository orderRepository;
    private PaymentFailedProcessor paymentFailedProcessor;

    @BeforeEach
    void setUp() {
        processedEventService = mock(ProcessedEventService.class);
        orderRepository = mock(OrderRepository.class);
        paymentFailedProcessor = new PaymentFailedProcessor(processedEventService, orderRepository);
        lenient().when(processedEventService.executeOnce(any(), any())).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return true;
        });
    }

    @Test
    void process_pendingOrder_shouldTransitionToFailed() {
        // given
        UUID eventId = UUID.randomUUID();
        UUID orderId = OrderFixture.ORDER_ID;
        PaymentFailedPayload payload = new PaymentFailedPayload(orderId, OrderFixture.PAYMENT_ID, OrderFixture.BUYER_ID, "Failed PG confirm", LocalDateTime.now());

        Order order = OrderFixture.createPendingOrderWithProducts();
        when(orderRepository.findByIdWithOrderProducts(orderId)).thenReturn(Optional.of(order));

        // when
        paymentFailedProcessor.process(new ConsumedEventContext(eventId, "PAYMENT_FAILED", LocalDateTime.now()), payload);

        // then
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.FAILED);
        verify(processedEventService).executeOnce(any(ConsumedEventContext.class), any(Runnable.class));
    }

    @Test
    void process_alreadyFailedOrder_shouldIdempotentlySucceedWithoutException() {
        // given
        UUID eventId = UUID.randomUUID();
        UUID orderId = OrderFixture.ORDER_ID;
        PaymentFailedPayload payload = new PaymentFailedPayload(orderId, OrderFixture.PAYMENT_ID, OrderFixture.BUYER_ID, "Failed PG confirm", LocalDateTime.now());

        Order order = OrderFixture.createPendingOrderWithProducts();
        order.markFailed(); // status FAILED
        when(orderRepository.findByIdWithOrderProducts(orderId)).thenReturn(Optional.of(order));

        // when
        paymentFailedProcessor.process(new ConsumedEventContext(eventId, "PAYMENT_FAILED", LocalDateTime.now()), payload);

        // then
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.FAILED);
        verify(processedEventService).executeOnce(any(ConsumedEventContext.class), any(Runnable.class));
    }

    @Test
    void process_alreadyPaidOrder_shouldReturnGracefully() {
        // given
        UUID eventId = UUID.randomUUID();
        UUID orderId = OrderFixture.ORDER_ID;
        PaymentFailedPayload payload = new PaymentFailedPayload(orderId, OrderFixture.PAYMENT_ID, OrderFixture.BUYER_ID, "Failed PG confirm", LocalDateTime.now());

        Order order = OrderFixture.createPaidOrderWithProducts(); // status PAID
        when(orderRepository.findByIdWithOrderProducts(orderId)).thenReturn(Optional.of(order));

        // when
        paymentFailedProcessor.process(new ConsumedEventContext(eventId, "PAYMENT_FAILED", LocalDateTime.now()), payload);

        // then
        verify(processedEventService).executeOnce(any(ConsumedEventContext.class), any(Runnable.class));
    }

    @Test
    void process_alreadyCanceledOrder_shouldReturnGracefully() {
        // given
        UUID eventId = UUID.randomUUID();
        UUID orderId = OrderFixture.ORDER_ID;
        PaymentFailedPayload payload = new PaymentFailedPayload(orderId, OrderFixture.PAYMENT_ID, OrderFixture.BUYER_ID, "Failed PG confirm", LocalDateTime.now());

        Order order = OrderFixture.createPendingOrderWithProducts();
        order.markCanceled(); // status CANCELED
        when(orderRepository.findByIdWithOrderProducts(orderId)).thenReturn(Optional.of(order));

        // when
        paymentFailedProcessor.process(new ConsumedEventContext(eventId, "PAYMENT_FAILED", LocalDateTime.now()), payload);

        // then
        verify(processedEventService).executeOnce(any(ConsumedEventContext.class), any(Runnable.class));
    }

    @Test
    void process_alreadyRefundedOrder_shouldReturnGracefully() {
        // given
        UUID eventId = UUID.randomUUID();
        UUID orderId = OrderFixture.ORDER_ID;
        PaymentFailedPayload payload = new PaymentFailedPayload(orderId, OrderFixture.PAYMENT_ID, OrderFixture.BUYER_ID, "Failed PG confirm", LocalDateTime.now());

        Order order = OrderFixture.createPendingOrderWithProducts();
        order.markCanceled(); // Hack to transition easily if refunded is complicated, let's use Reflection or direct transition
        org.springframework.test.util.ReflectionTestUtils.setField(order, "orderStatus", OrderStatus.REFUNDED);

        when(orderRepository.findByIdWithOrderProducts(orderId)).thenReturn(Optional.of(order));

        // when
        paymentFailedProcessor.process(new ConsumedEventContext(eventId, "PAYMENT_FAILED", LocalDateTime.now()), payload);

        // then
        verify(processedEventService).executeOnce(any(ConsumedEventContext.class), any(Runnable.class));
    }

    @Test
    void process_duplicateEvent_shouldReturnEarly() {
        // given
        UUID eventId = UUID.randomUUID();
        UUID orderId = OrderFixture.ORDER_ID;
        PaymentFailedPayload payload = new PaymentFailedPayload(orderId, OrderFixture.PAYMENT_ID, OrderFixture.BUYER_ID, "Failed PG confirm", LocalDateTime.now());

        doReturn(false).when(processedEventService).executeOnce(any(), any());

        // when
        paymentFailedProcessor.process(new ConsumedEventContext(eventId, "PAYMENT_FAILED", LocalDateTime.now()), payload);

        // then
        verifyNoInteractions(orderRepository);
    }

    @Test
    void process_orderNotFound_shouldThrowException() {
        // given
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        PaymentFailedPayload payload = new PaymentFailedPayload(orderId, OrderFixture.PAYMENT_ID, OrderFixture.BUYER_ID, "Failed PG confirm", LocalDateTime.now());

        when(orderRepository.findByIdWithOrderProducts(orderId)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> paymentFailedProcessor.process(new ConsumedEventContext(eventId, "PAYMENT_FAILED", LocalDateTime.now()), payload))
                .isInstanceOf(OrderException.class)
                .hasFieldOrPropertyWithValue("errorCode", com.prompthub.order.global.exception.ErrorCode.ORDER_NOT_FOUND);

    }
}
