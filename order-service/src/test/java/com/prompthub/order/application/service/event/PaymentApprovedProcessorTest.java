package com.prompthub.order.application.service.event;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.application.event.order.OrderPaidEvent;
import com.prompthub.order.application.service.event.outbox.OutboxEventAppender;
import com.prompthub.order.domain.enums.OrderProductStatus;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Cart;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.repository.CartRepository;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.infra.messaging.kafka.event.OrderPaidPayload;
import com.prompthub.order.infra.messaging.kafka.event.PaymentApprovedOrderPayload;
import com.prompthub.order.infra.messaging.kafka.event.PaymentApprovedPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.prompthub.order.fixture.PaymentEventFixture.APPROVED_AT;
import static com.prompthub.order.fixture.PaymentEventFixture.BUYER_ID;
import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_A;
import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_B;
import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_PRODUCT_A;
import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_PRODUCT_B;
import static com.prompthub.order.fixture.PaymentEventFixture.OTHER_BUYER_ID;
import static com.prompthub.order.fixture.PaymentEventFixture.PAYMENT_ID;
import static com.prompthub.order.fixture.PaymentEventFixture.PRODUCT_A;
import static com.prompthub.order.fixture.PaymentEventFixture.PRODUCT_B;
import static com.prompthub.order.fixture.PaymentEventFixture.SELLER_B;
import static com.prompthub.order.fixture.PaymentEventFixture.approvedPayload;
import static com.prompthub.order.fixture.PaymentEventFixture.createdOrders;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class PaymentApprovedProcessorTest {

	@Mock
	private ProcessedEventService processedEventService;

	@Mock
	private OrderRepository orderRepository;

	@Mock
	private CartRepository cartRepository;

	@Mock
	private OrderEventMessageFactory orderEventMessageFactory;

	@Mock
	private OutboxEventAppender outboxEventAppender;

	@Mock
	private ApplicationEventPublisher applicationEventPublisher;

	@Spy
	private PaymentEventValidator validator = new PaymentEventValidator();

	@InjectMocks
	private PaymentApprovedProcessor processor;

	@Test
	void process_multipleOrders_completesAllAndCreatesOutboxPerOrder() {
		List<Order> orders = createdOrders();
		PaymentApprovedPayload payload = approvedPayload(orders);
		Cart cart = mock(Cart.class);
		UUID eventId = UUID.randomUUID();
		given(processedEventService.isProcessed(eventId, "order-service")).willReturn(false);
		given(orderRepository.findAllByIdsWithOrderProductsForUpdate(List.of(ORDER_A, ORDER_B)))
			.willReturn(orders);
		given(cartRepository.findByBuyerIdWithCartProducts(BUYER_ID)).willReturn(Optional.of(cart));
		stubOrderPaidMessages();

		processor.process(eventId, "PAYMENT_APPROVED", APPROVED_AT, payload);

		assertThat(orders)
			.extracting(Order::getOrderStatus)
			.containsOnly(OrderStatus.COMPLETED);
		assertThat(orders)
			.flatExtracting(Order::getOrderProducts)
			.extracting(OrderProduct::getOrderStatus)
			.containsOnly(OrderProductStatus.PAID);
		then(orderEventMessageFactory).should().createOrderPaidMessage(eq(ORDER_A), any());
		then(orderEventMessageFactory).should().createOrderPaidMessage(eq(ORDER_B), any());
		then(outboxEventAppender).should(times(2)).append(any());
		then(cart).should().removeProductsByProductIds(List.of(PRODUCT_A, PRODUCT_B));
		then(processedEventService).should()
			.markProcessed(eventId, "order-service", "PAYMENT_APPROVED", APPROVED_AT);
		ArgumentCaptor<OrderPaidEvent> eventCaptor = ArgumentCaptor.forClass(OrderPaidEvent.class);
		then(applicationEventPublisher).should().publishEvent(eventCaptor.capture());
		assertThat(eventCaptor.getValue().orderIds()).containsExactly(ORDER_A, ORDER_B);
	}

	@Test
	void process_failedOrders_recoversToCompleted() {
		List<Order> orders = createdOrders();
		orders.forEach(Order::markFailed);
		UUID eventId = UUID.randomUUID();
		stubTargets(eventId, orders);
		stubOrderPaidMessages();

		processor.process(eventId, "PAYMENT_APPROVED", APPROVED_AT, approvedPayload(orders));

		assertThat(orders)
			.extracting(Order::getOrderStatus)
			.containsOnly(OrderStatus.COMPLETED);
	}

	@Test
	void process_completedOrders_doNotCreateDuplicateOutbox() {
		List<Order> orders = createdOrders();
		orders.forEach(order -> order.markCompleted(APPROVED_AT));
		UUID eventId = UUID.randomUUID();
		stubTargets(eventId, orders);

		processor.process(eventId, "PAYMENT_APPROVED", APPROVED_AT, approvedPayload(orders));

		then(orderEventMessageFactory).shouldHaveNoInteractions();
		then(outboxEventAppender).shouldHaveNoInteractions();
		then(applicationEventPublisher).shouldHaveNoInteractions();
		then(processedEventService).should()
			.markProcessed(eventId, "order-service", "PAYMENT_APPROVED", APPROVED_AT);
	}

	@Test
	void process_foreignOrderProduct_throwsInvalidInput() {
		List<Order> orders = createdOrders();
		PaymentApprovedPayload payload = new PaymentApprovedPayload(
			PAYMENT_ID,
			BUYER_ID,
			30_000,
			List.of(
				new PaymentApprovedOrderPayload(ORDER_A, List.of(UUID.randomUUID())),
				new PaymentApprovedOrderPayload(ORDER_B, List.of(ORDER_PRODUCT_B))
			),
			APPROVED_AT
		);
		UUID eventId = UUID.randomUUID();
		given(processedEventService.isProcessed(eventId, "order-service")).willReturn(false);
		given(orderRepository.findAllByIdsWithOrderProductsForUpdate(List.of(ORDER_A, ORDER_B)))
			.willReturn(orders);

		assertThatThrownBy(() -> processor.process(eventId, "PAYMENT_APPROVED", APPROVED_AT, payload))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_VALUE);
		assertThat(orders)
			.extracting(Order::getOrderStatus)
			.containsOnly(OrderStatus.CREATED);
		then(outboxEventAppender).shouldHaveNoInteractions();
	}

	@Test
	void process_missingOrder_throwsBeforeMutation() {
		List<Order> orders = createdOrders();
		PaymentApprovedPayload payload = approvedPayload(orders);
		UUID eventId = UUID.randomUUID();
		given(processedEventService.isProcessed(eventId, "order-service")).willReturn(false);
		given(orderRepository.findAllByIdsWithOrderProductsForUpdate(List.of(ORDER_A, ORDER_B)))
			.willReturn(List.of(orders.getFirst()));

		assertThatThrownBy(() -> processor.process(eventId, "PAYMENT_APPROVED", APPROVED_AT, payload))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_NOT_FOUND);
		assertThat(orders)
			.extracting(Order::getOrderStatus)
			.containsOnly(OrderStatus.CREATED);
		then(outboxEventAppender).shouldHaveNoInteractions();
	}

	@Test
	void process_buyerMismatch_throwsBeforeMutation() {
		List<Order> orders = new ArrayList<>(createdOrders());
		orders.set(1, com.prompthub.order.fixture.PaymentEventFixture.order(
			ORDER_B,
			ORDER_PRODUCT_B,
			PRODUCT_B,
			SELLER_B,
			OTHER_BUYER_ID,
			"ORD-B",
			20_000
		));
		PaymentApprovedPayload payload = approvedPayload(orders);
		UUID eventId = UUID.randomUUID();
		given(processedEventService.isProcessed(eventId, "order-service")).willReturn(false);
		given(orderRepository.findAllByIdsWithOrderProductsForUpdate(List.of(ORDER_A, ORDER_B)))
			.willReturn(orders);

		assertThatThrownBy(() -> processor.process(eventId, "PAYMENT_APPROVED", APPROVED_AT, payload))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_VALUE);
		assertThat(orders)
			.extracting(Order::getOrderStatus)
			.containsOnly(OrderStatus.CREATED);
	}

	@Test
	void process_totalAmountIsNotCorrelatedInThisIssue() {
		List<Order> orders = createdOrders();
		PaymentApprovedPayload original = approvedPayload(orders);
		PaymentApprovedPayload payload = new PaymentApprovedPayload(
			original.paymentId(),
			original.buyerId(),
			1,
			original.orders(),
			original.approvedAt()
		);
		UUID eventId = UUID.randomUUID();
		stubTargets(eventId, orders);
		stubOrderPaidMessages();

		processor.process(eventId, "PAYMENT_APPROVED", APPROVED_AT, payload);

		assertThat(orders)
			.extracting(Order::getOrderStatus)
			.containsOnly(OrderStatus.COMPLETED);
	}

	@Test
	void process_sameEventId_returnsBeforeLocking() {
		UUID eventId = UUID.randomUUID();
		given(processedEventService.isProcessed(eventId, "order-service")).willReturn(true);

		processor.process(eventId, "PAYMENT_APPROVED", APPROVED_AT, approvedPayload(createdOrders()));

		then(orderRepository).shouldHaveNoInteractions();
		then(outboxEventAppender).shouldHaveNoInteractions();
	}

	@Test
	void process_eventCompletedWhileWaitingForLock_returnsWithoutMutation() {
		List<Order> orders = createdOrders();
		UUID eventId = UUID.randomUUID();
		given(processedEventService.isProcessed(eventId, "order-service"))
			.willReturn(false, true);
		given(orderRepository.findAllByIdsWithOrderProductsForUpdate(List.of(ORDER_A, ORDER_B)))
			.willReturn(orders);

		processor.process(eventId, "PAYMENT_APPROVED", APPROVED_AT, approvedPayload(orders));

		assertThat(orders)
			.extracting(Order::getOrderStatus)
			.containsOnly(OrderStatus.CREATED);
		then(outboxEventAppender).shouldHaveNoInteractions();
		then(processedEventService).should(never()).markProcessed(any(), any(), any(), any());
	}

	private void stubTargets(UUID eventId, List<Order> orders) {
		given(processedEventService.isProcessed(eventId, "order-service")).willReturn(false);
		given(orderRepository.findAllByIdsWithOrderProductsForUpdate(List.of(ORDER_A, ORDER_B)))
			.willReturn(orders);
		given(cartRepository.findByBuyerIdWithCartProducts(BUYER_ID)).willReturn(Optional.empty());
	}

	private void stubOrderPaidMessages() {
		given(orderEventMessageFactory.createOrderPaidMessage(any(), any()))
			.willAnswer(invocation -> {
				UUID orderId = invocation.getArgument(0);
				OrderPaidPayload payload = invocation.getArgument(1);
				return new EventMessage<>(
					UUID.randomUUID(),
					"ORDER_PAID",
					APPROVED_AT,
					"ORDER",
					orderId,
					payload
				);
			});
	}
}
