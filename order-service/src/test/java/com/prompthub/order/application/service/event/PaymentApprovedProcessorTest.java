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
import com.prompthub.order.infra.messaging.kafka.event.PaymentApprovedPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static com.prompthub.order.fixture.PaymentEventFixture.APPROVED_AT;
import static com.prompthub.order.fixture.PaymentEventFixture.APPROVED_AT_OFFSET;
import static com.prompthub.order.fixture.PaymentEventFixture.BUYER_ID;
import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_A;
import static com.prompthub.order.fixture.PaymentEventFixture.OTHER_BUYER_ID;
import static com.prompthub.order.fixture.PaymentEventFixture.PAYMENT_ID;
import static com.prompthub.order.fixture.PaymentEventFixture.SELLER_A;
import static com.prompthub.order.fixture.PaymentEventFixture.SELLER_B;
import static com.prompthub.order.fixture.PaymentEventFixture.SELLER_C;
import static com.prompthub.order.fixture.PaymentEventFixture.approvedPayload;
import static com.prompthub.order.fixture.PaymentEventFixture.createdOrder;
import static com.prompthub.order.fixture.PaymentEventFixture.productIds;
import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_PRODUCT_A;
import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_PRODUCT_B;
import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_PRODUCT_C;
import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_PRODUCT_D;
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
	void process_createdOrder_completesFourProductsAndCreatesOneOutboxAndCleanupEvent() {
		Order order = createdOrder();
		PaymentApprovedPayload payload = approvedPayload(order);
		Cart cart = mock(Cart.class);
		UUID eventId = UUID.randomUUID();
		given(processedEventService.isProcessed(eventId, "order-service")).willReturn(false);
		given(orderRepository.findByIdWithOrderProductsForUpdate(ORDER_A)).willReturn(Optional.of(order));
		given(cartRepository.findByBuyerIdWithCartProducts(BUYER_ID)).willReturn(Optional.of(cart));
		stubOrderPaidMessage();

		processor.process(eventId, "PAYMENT_APPROVED", APPROVED_AT, payload);

		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
		assertThat(order.getCompletedAt()).isEqualTo(APPROVED_AT);
		assertThat(order.getOrderProducts())
			.extracting(OrderProduct::getOrderStatus)
			.containsOnly(OrderProductStatus.PAID);
		ArgumentCaptor<OrderPaidPayload> paidPayloadCaptor = ArgumentCaptor.forClass(OrderPaidPayload.class);
		then(orderEventMessageFactory).should().createOrderPaidMessage(eq(ORDER_A), paidPayloadCaptor.capture());
		assertThat(paidPayloadCaptor.getValue().products())
			.extracting(product -> product.sellerId())
			.containsExactly(SELLER_A, SELLER_B, SELLER_A, SELLER_C);
		then(outboxEventAppender).should().append(any());
		then(cart).should().removeProductsByProductIds(productIds());
		then(processedEventService).should()
			.markProcessed(eventId, "order-service", "PAYMENT_APPROVED", APPROVED_AT);
		ArgumentCaptor<OrderPaidEvent> eventCaptor = ArgumentCaptor.forClass(OrderPaidEvent.class);
		then(applicationEventPublisher).should().publishEvent(eventCaptor.capture());
		assertThat(eventCaptor.getValue().orderId()).isEqualTo(ORDER_A);
	}

	@Test
	void process_failedOrder_recoversToCompleted() {
		Order order = createdOrder();
		order.markFailed();
		UUID eventId = UUID.randomUUID();
		stubTarget(eventId, order);
		given(cartRepository.findByBuyerIdWithCartProducts(BUYER_ID)).willReturn(Optional.empty());
		stubOrderPaidMessage();

		processor.process(eventId, "PAYMENT_APPROVED", APPROVED_AT, approvedPayload(order));

		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
		assertThat(order.getOrderProducts())
			.extracting(OrderProduct::getOrderStatus)
			.containsOnly(OrderProductStatus.PAID);
	}

	@ParameterizedTest
	@EnumSource(value = OrderStatus.class, names = {"COMPLETED", "PARTIAL_REFUNDED", "ALL_REFUNDED"})
	void process_lateApproval_marksOnlyProcessedAndPreservesReaddedCartProduct(OrderStatus status) {
		Order order = createdOrder();
		order.markPaid();
		if (status == OrderStatus.PARTIAL_REFUNDED) {
			order.refundOrderProduct(ORDER_PRODUCT_A, 10_000, APPROVED_AT);
		} else if (status == OrderStatus.ALL_REFUNDED) {
			order.refundOrderProduct(ORDER_PRODUCT_A, 10_000, APPROVED_AT);
			order.refundOrderProduct(ORDER_PRODUCT_B, 20_000, APPROVED_AT);
			order.refundOrderProduct(ORDER_PRODUCT_C, 30_000, APPROVED_AT);
			order.refundOrderProduct(ORDER_PRODUCT_D, 40_000, APPROVED_AT);
		}
		UUID eventId = UUID.randomUUID();
		stubTarget(eventId, order);

		processor.process(eventId, "PAYMENT_APPROVED", APPROVED_AT, approvedPayload(order));

		then(orderEventMessageFactory).shouldHaveNoInteractions();
		then(outboxEventAppender).shouldHaveNoInteractions();
		then(cartRepository).shouldHaveNoInteractions();
		then(applicationEventPublisher).shouldHaveNoInteractions();
		then(processedEventService).should()
			.markProcessed(eventId, "order-service", "PAYMENT_APPROVED", APPROVED_AT);
		assertThat(order.getOrderStatus()).isEqualTo(status);
	}

	@Test
	void process_buyerMismatch_rejectsWithoutMutation() {
		Order order = createdOrder();
		PaymentApprovedPayload payload = new PaymentApprovedPayload(
			PAYMENT_ID,
			ORDER_A,
			OTHER_BUYER_ID,
			order.getTotalOrderAmount(),
			APPROVED_AT_OFFSET
		);
		UUID eventId = UUID.randomUUID();
		stubTarget(eventId, order);

		assertThatThrownBy(() -> processor.process(eventId, "PAYMENT_APPROVED", APPROVED_AT, payload))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_ACCESS_DENIED);
		assertUnchanged(order);
	}

	@Test
	void process_amountMismatch_rejectsWithoutMutation() {
		Order order = createdOrder();
		PaymentApprovedPayload payload = new PaymentApprovedPayload(
			PAYMENT_ID,
			ORDER_A,
			BUYER_ID,
			order.getTotalOrderAmount() - 1,
			APPROVED_AT_OFFSET
		);
		UUID eventId = UUID.randomUUID();
		stubTarget(eventId, order);

		assertThatThrownBy(() -> processor.process(eventId, "PAYMENT_APPROVED", APPROVED_AT, payload))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_PAYMENT_AMOUNT_MISMATCH);
		assertUnchanged(order);
	}

	@Test
	void process_missingOrder_throwsBeforeMutation() {
		Order order = createdOrder();
		UUID eventId = UUID.randomUUID();
		given(processedEventService.isProcessed(eventId, "order-service")).willReturn(false);
		given(orderRepository.findByIdWithOrderProductsForUpdate(ORDER_A)).willReturn(Optional.empty());

		assertThatThrownBy(() -> processor.process(eventId, "PAYMENT_APPROVED", APPROVED_AT, approvedPayload(order)))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_NOT_FOUND);
		then(outboxEventAppender).shouldHaveNoInteractions();
	}

	@Test
	void process_sameEventId_returnsBeforeLocking() {
		UUID eventId = UUID.randomUUID();
		given(processedEventService.isProcessed(eventId, "order-service")).willReturn(true);

		processor.process(eventId, "PAYMENT_APPROVED", APPROVED_AT, approvedPayload(createdOrder()));

		then(orderRepository).shouldHaveNoInteractions();
		then(outboxEventAppender).shouldHaveNoInteractions();
	}

	@Test
	void process_eventCompletedWhileWaitingForLock_returnsWithoutMutation() {
		Order order = createdOrder();
		UUID eventId = UUID.randomUUID();
		given(processedEventService.isProcessed(eventId, "order-service")).willReturn(false, true);
		given(orderRepository.findByIdWithOrderProductsForUpdate(ORDER_A)).willReturn(Optional.of(order));

		processor.process(eventId, "PAYMENT_APPROVED", APPROVED_AT, approvedPayload(order));

		assertUnchanged(order);
		then(processedEventService).should(never()).markProcessed(any(), any(), any(), any());
	}

	private void assertUnchanged(Order order) {
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CREATED);
		assertThat(order.getOrderProducts())
			.extracting(OrderProduct::getOrderStatus)
			.containsOnly(OrderProductStatus.PENDING);
		then(outboxEventAppender).shouldHaveNoInteractions();
		then(cartRepository).shouldHaveNoInteractions();
		then(applicationEventPublisher).shouldHaveNoInteractions();
		then(processedEventService).should(never()).markProcessed(any(), any(), any(), any());
	}

	private void stubTarget(UUID eventId, Order order) {
		given(processedEventService.isProcessed(eventId, "order-service")).willReturn(false);
		given(orderRepository.findByIdWithOrderProductsForUpdate(ORDER_A)).willReturn(Optional.of(order));
	}

	private void stubOrderPaidMessage() {
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
