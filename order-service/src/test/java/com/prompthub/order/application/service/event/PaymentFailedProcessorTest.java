package com.prompthub.order.application.service.event;

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
    }

    @Test
    void process_pendingOrder_shouldTransitionToFailed() {
        // given
        UUID eventId = UUID.randomUUID();
        UUID orderId = OrderFixture.ORDER_ID;
        PaymentFailedPayload payload = new PaymentFailedPayload(orderId, OrderFixture.PAYMENT_ID, OrderFixture.BUYER_ID, "Failed PG confirm", LocalDateTime.now());

        Order order = OrderFixture.createPendingOrderWithProducts();
        when(processedEventService.isProcessed(eventId, "order-service")).thenReturn(false);
        when(orderRepository.findByIdWithOrderProducts(orderId)).thenReturn(Optional.of(order));

        // when
        paymentFailedProcessor.process(eventId, "PAYMENT_FAILED", LocalDateTime.now(), payload);

        // then
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.FAILED);
        verify(processedEventService).markProcessed(eq(eventId), eq("order-service"), eq("PAYMENT_FAILED"), any());
    }

    @Test
    void process_alreadyFailedOrder_shouldIdempotentlySucceedWithoutException() {
        // given
        UUID eventId = UUID.randomUUID();
        UUID orderId = OrderFixture.ORDER_ID;
        PaymentFailedPayload payload = new PaymentFailedPayload(orderId, OrderFixture.PAYMENT_ID, OrderFixture.BUYER_ID, "Failed PG confirm", LocalDateTime.now());

        Order order = OrderFixture.createPendingOrderWithProducts();
        order.markFailed(); // status FAILED
        when(processedEventService.isProcessed(eventId, "order-service")).thenReturn(false);
        when(orderRepository.findByIdWithOrderProducts(orderId)).thenReturn(Optional.of(order));

        // when
        paymentFailedProcessor.process(eventId, "PAYMENT_FAILED", LocalDateTime.now(), payload);

        // then
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.FAILED);
        verify(processedEventService).markProcessed(eq(eventId), eq("order-service"), eq("PAYMENT_FAILED"), any());
    }

    @Test
    void process_alreadyPaidOrder_shouldReturnGracefully() {
        // given
        UUID eventId = UUID.randomUUID();
        UUID orderId = OrderFixture.ORDER_ID;
        PaymentFailedPayload payload = new PaymentFailedPayload(orderId, OrderFixture.PAYMENT_ID, OrderFixture.BUYER_ID, "Failed PG confirm", LocalDateTime.now());

        Order order = OrderFixture.createPaidOrderWithProducts(); // status PAID
        when(processedEventService.isProcessed(eventId, "order-service")).thenReturn(false);
        when(orderRepository.findByIdWithOrderProducts(orderId)).thenReturn(Optional.of(order));

        // when
        paymentFailedProcessor.process(eventId, "PAYMENT_FAILED", LocalDateTime.now(), payload);

        // then
        verify(processedEventService).markProcessed(eq(eventId), eq("order-service"), eq("PAYMENT_FAILED"), any());
    }

    @Test
    void process_duplicateEvent_shouldReturnEarly() {
        // given
        UUID eventId = UUID.randomUUID();
        UUID orderId = OrderFixture.ORDER_ID;
        PaymentFailedPayload payload = new PaymentFailedPayload(orderId, OrderFixture.PAYMENT_ID, OrderFixture.BUYER_ID, "Failed PG confirm", LocalDateTime.now());

        when(processedEventService.isProcessed(eventId, "order-service")).thenReturn(true);

        // when
        paymentFailedProcessor.process(eventId, "PAYMENT_FAILED", LocalDateTime.now(), payload);

        // then
        verifyNoInteractions(orderRepository);
        verify(processedEventService, never()).markProcessed(any(), any(), any(), any());
    }
}
