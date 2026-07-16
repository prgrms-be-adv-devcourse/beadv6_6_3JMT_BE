package com.prompthub.order.application.service.event;

import com.prompthub.order.domain.enums.OrderProductStatus;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static com.prompthub.order.fixture.PaymentEventFixture.APPROVED_AT;
import static com.prompthub.order.fixture.PaymentEventFixture.FAILED_AT;
import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_A;
import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_B;
import static com.prompthub.order.fixture.PaymentEventFixture.createdOrders;
import static com.prompthub.order.fixture.PaymentEventFixture.failedPayload;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class PaymentFailedProcessorTest {

	@Mock
	private ProcessedEventService processedEventService;

	@Mock
	private OrderRepository orderRepository;

	@Spy
	private PaymentEventValidator validator = new PaymentEventValidator();

	@InjectMocks
	private PaymentFailedProcessor processor;

	@Test
	void process_multipleCreatedOrders_marksEveryOrderAndProductFailed() {
		List<Order> orders = createdOrders();
		UUID eventId = UUID.randomUUID();
		given(processedEventService.isProcessed(eventId, "order-service")).willReturn(false);
		given(orderRepository.findAllByIdsWithOrderProductsForUpdate(List.of(ORDER_A, ORDER_B)))
			.willReturn(orders);

		processor.process(eventId, "PAYMENT_FAILED", FAILED_AT, failedPayload());

		assertThat(orders)
			.extracting(Order::getOrderStatus)
			.containsOnly(OrderStatus.FAILED);
		assertThat(orders)
			.flatExtracting(Order::getOrderProducts)
			.extracting(OrderProduct::getOrderStatus)
			.containsOnly(OrderProductStatus.FAILED);
		then(processedEventService).should()
			.markProcessed(eventId, "order-service", "PAYMENT_FAILED", FAILED_AT);
	}

	@Test
	void process_lateFailure_doesNotReverseCompletedOrFailedOrders() {
		List<Order> orders = createdOrders();
		orders.get(0).markFailed();
		orders.get(1).markCompleted(APPROVED_AT);
		UUID eventId = UUID.randomUUID();
		given(processedEventService.isProcessed(eventId, "order-service")).willReturn(false);
		given(orderRepository.findAllByIdsWithOrderProductsForUpdate(List.of(ORDER_A, ORDER_B)))
			.willReturn(orders);

		processor.process(eventId, "PAYMENT_FAILED", FAILED_AT, failedPayload());

		assertThat(orders.get(0).getOrderStatus()).isEqualTo(OrderStatus.FAILED);
		assertThat(orders.get(1).getOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
	}

	@Test
	void process_missingOrder_throwsBeforeStateChange() {
		List<Order> orders = createdOrders();
		UUID eventId = UUID.randomUUID();
		given(processedEventService.isProcessed(eventId, "order-service")).willReturn(false);
		given(orderRepository.findAllByIdsWithOrderProductsForUpdate(List.of(ORDER_A, ORDER_B)))
			.willReturn(List.of(orders.getFirst()));

		assertThatThrownBy(() -> processor.process(eventId, "PAYMENT_FAILED", FAILED_AT, failedPayload()))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_NOT_FOUND);
		assertThat(orders.getFirst().getOrderStatus()).isEqualTo(OrderStatus.CREATED);
		then(processedEventService).should(never()).markProcessed(any(), any(), any(), any());
	}

	@Test
	void process_sameEventId_returnsBeforeLocking() {
		UUID eventId = UUID.randomUUID();
		given(processedEventService.isProcessed(eventId, "order-service")).willReturn(true);

		processor.process(eventId, "PAYMENT_FAILED", FAILED_AT, failedPayload());

		then(orderRepository).shouldHaveNoInteractions();
		then(processedEventService).should(never()).markProcessed(any(), any(), any(), any());
	}

	@Test
	void process_eventCompletedWhileWaitingForLock_returnsWithoutStateChange() {
		List<Order> orders = createdOrders();
		UUID eventId = UUID.randomUUID();
		given(processedEventService.isProcessed(eventId, "order-service"))
			.willReturn(false, true);
		given(orderRepository.findAllByIdsWithOrderProductsForUpdate(List.of(ORDER_A, ORDER_B)))
			.willReturn(orders);

		processor.process(eventId, "PAYMENT_FAILED", FAILED_AT, failedPayload());

		assertThat(orders)
			.extracting(Order::getOrderStatus)
			.containsOnly(OrderStatus.CREATED);
		then(processedEventService).should(never()).markProcessed(any(), any(), any(), any());
	}
}
