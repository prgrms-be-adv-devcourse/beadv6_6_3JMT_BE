package com.prompthub.order.application.service.event.payment;

import com.prompthub.order.application.service.event.common.ConsumedEventContext;
import com.prompthub.order.application.service.event.common.ProcessedEventService;

import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.fixture.OrderFixture;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.infra.messaging.kafka.event.PaymentCanceledPayload;
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

class PaymentCanceledProcessorTest {

    private ProcessedEventService processedEventService;
    private OrderRepository orderRepository;
    private PaymentCanceledProcessor paymentCanceledProcessor;

    @BeforeEach
    void setUp() {
        processedEventService = mock(ProcessedEventService.class);
        orderRepository = mock(OrderRepository.class);
        paymentCanceledProcessor = new PaymentCanceledProcessor(processedEventService, orderRepository);
        lenient().when(processedEventService.executeOnce(any(), any())).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return true;
        });
    }

    @Test
    void process_pendingOrder_shouldTransitionToCanceled() {
        // given
        UUID eventId = UUID.randomUUID();
        UUID orderId = OrderFixture.ORDER_ID;
        PaymentCanceledPayload payload = new PaymentCanceledPayload(orderId, OrderFixture.PAYMENT_ID, OrderFixture.BUYER_ID, LocalDateTime.now());

        Order order = OrderFixture.createPendingOrderWithProducts();
        when(orderRepository.findByIdWithOrderProducts(orderId)).thenReturn(Optional.of(order));

        // when
        paymentCanceledProcessor.process(new ConsumedEventContext(eventId, "PAYMENT_CANCELED", LocalDateTime.now()), payload);

        // then
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CANCELED);
        verify(processedEventService).executeOnce(any(ConsumedEventContext.class), any(Runnable.class));
    }

    @Test
    void process_alreadyCanceledOrder_shouldIdempotentlySucceedWithoutException() {
        // given
        UUID eventId = UUID.randomUUID();
        UUID orderId = OrderFixture.ORDER_ID;
        PaymentCanceledPayload payload = new PaymentCanceledPayload(orderId, OrderFixture.PAYMENT_ID, OrderFixture.BUYER_ID, LocalDateTime.now());

        Order order = OrderFixture.createPendingOrderWithProducts();
        order.markCanceled(); // status CANCELED
        when(orderRepository.findByIdWithOrderProducts(orderId)).thenReturn(Optional.of(order));

        // when
        paymentCanceledProcessor.process(new ConsumedEventContext(eventId, "PAYMENT_CANCELED", LocalDateTime.now()), payload);

        // then
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CANCELED);
        verify(processedEventService).executeOnce(any(ConsumedEventContext.class), any(Runnable.class));
    }

    @Test
    void process_alreadyPaidOrder_shouldReturnGracefully() {
        // given
        UUID eventId = UUID.randomUUID();
        UUID orderId = OrderFixture.ORDER_ID;
        PaymentCanceledPayload payload = new PaymentCanceledPayload(orderId, OrderFixture.PAYMENT_ID, OrderFixture.BUYER_ID, LocalDateTime.now());

        Order order = OrderFixture.createPaidOrderWithProducts(); // status PAID
        when(orderRepository.findByIdWithOrderProducts(orderId)).thenReturn(Optional.of(order));

        // when
        paymentCanceledProcessor.process(new ConsumedEventContext(eventId, "PAYMENT_CANCELED", LocalDateTime.now()), payload);

        // then
        verify(processedEventService).executeOnce(any(ConsumedEventContext.class), any(Runnable.class));
    }

    @Test
    void process_duplicateEvent_shouldReturnEarly() {
        // given
        UUID eventId = UUID.randomUUID();
        UUID orderId = OrderFixture.ORDER_ID;
        PaymentCanceledPayload payload = new PaymentCanceledPayload(orderId, OrderFixture.PAYMENT_ID, OrderFixture.BUYER_ID, LocalDateTime.now());

        doReturn(false).when(processedEventService).executeOnce(any(), any());

        // when
        paymentCanceledProcessor.process(new ConsumedEventContext(eventId, "PAYMENT_CANCELED", LocalDateTime.now()), payload);

        // then
        verifyNoInteractions(orderRepository);
    }
}
