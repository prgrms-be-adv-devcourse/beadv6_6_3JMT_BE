package com.prompthub.order.application.service.event;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.application.service.event.outbox.OutboxEventAppender;
import com.prompthub.order.domain.enums.OrderProductStatus;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.infra.messaging.kafka.event.OrderRefundPayload;
import com.prompthub.order.infra.messaging.kafka.event.PaymentRefundedPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static com.prompthub.order.fixture.OrderFixture.BUYER_ID;
import static com.prompthub.order.fixture.OrderFixture.PAYMENT_ID;
import static com.prompthub.order.fixture.OrderFixture.PRODUCT_AMOUNT_1;
import static com.prompthub.order.fixture.OrderFixture.REFUNDED_AT;
import static com.prompthub.order.fixture.OrderFixture.SELLER_ID_1;
import static com.prompthub.order.fixture.OrderFixture.createPaidOrderWithProducts;
import static com.prompthub.order.fixture.OrderFixture.createPendingOrderWithProducts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class PaymentRefundedProcessorTest {

	private static final String CONSUMER_GROUP = "order-service";
	private static final String EVENT_TYPE = "PAYMENT_REFUNDED";
	private static final String REFUNDED_AT_OFFSET = "2026-06-19T12:20:00+09:00";

	@Mock
	private ProcessedEventService processedEventService;

	@Mock
	private OrderRepository orderRepository;

	@Mock
	private OrderEventMessageFactory orderEventMessageFactory;

	@Mock
	private OutboxEventAppender outboxEventAppender;

	@Spy
	private PaymentEventValidator validator = new PaymentEventValidator();

	@InjectMocks
	private PaymentRefundedProcessor processor;

	@Test
	void process_firstSellerProduct_refundsOnlyTargetAndCreatesSingleProductOutbox() {
		Order order = createPaidOrderWithProducts();
		OrderProduct target = order.getOrderProducts().getFirst();
		UUID eventId = UUID.randomUUID();
		PaymentRefundedPayload payload = payload(order, target, "PARTIAL_REFUNDED");
		stubTarget(eventId, order);
		stubOrderRefundMessage();

		processor.process(eventId, EVENT_TYPE, REFUNDED_AT, payload);

		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PARTIAL_REFUNDED);
		assertThat(order.getRefundedAt()).isNull();
		assertThat(order.getOrderProducts())
			.extracting(OrderProduct::getOrderStatus)
			.containsExactly(OrderProductStatus.REFUNDED, OrderProductStatus.PAID);
		ArgumentCaptor<OrderRefundPayload> captor = ArgumentCaptor.forClass(OrderRefundPayload.class);
		then(orderEventMessageFactory).should().createOrderRefundMessage(eq(order.getId()), captor.capture());
		assertThat(captor.getValue().products()).singleElement().satisfies(product -> {
			assertThat(product.orderProductId()).isEqualTo(target.getId());
			assertThat(product.sellerId()).isEqualTo(SELLER_ID_1);
			assertThat(product.productAmount()).isEqualTo(PRODUCT_AMOUNT_1);
		});
		then(orderRepository).should().findByIdWithOrderProductsForUpdate(order.getId());
		then(processedEventService).should(times(2)).isProcessed(eventId, CONSUMER_GROUP);
		then(outboxEventAppender).should().append(any());
		then(processedEventService).should()
			.markProcessed(eventId, CONSUMER_GROUP, EVENT_TYPE, REFUNDED_AT);
	}

	@Test
	void process_lastSellerProduct_recalculatesAllRefunded() {
		Order order = createPaidOrderWithProducts();
		OrderProduct first = order.getOrderProducts().getFirst();
		OrderProduct second = order.getOrderProducts().getLast();
		order.refundOrderProduct(first.getId(), first.getProductAmount(), REFUNDED_AT.minusMinutes(1));
		UUID eventId = UUID.randomUUID();
		stubTarget(eventId, order);
		stubOrderRefundMessage();

		processor.process(eventId, EVENT_TYPE, REFUNDED_AT, payload(order, second, "ALL_REFUNDED"));

		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.ALL_REFUNDED);
		assertThat(order.getRefundedAt()).isEqualTo(REFUNDED_AT);
		assertThat(order.getOrderProducts())
			.extracting(OrderProduct::getOrderStatus)
			.containsOnly(OrderProductStatus.REFUNDED);
	}

	@Test
	void process_sameEventId_returnsBeforeLocking() {
		Order order = createPaidOrderWithProducts();
		OrderProduct target = order.getOrderProducts().getFirst();
		UUID eventId = UUID.randomUUID();
		given(processedEventService.isProcessed(eventId, CONSUMER_GROUP)).willReturn(true);

		processor.process(eventId, EVENT_TYPE, REFUNDED_AT, payload(order, target, "PARTIAL_REFUNDED"));

		then(orderRepository).shouldHaveNoInteractions();
		then(outboxEventAppender).shouldHaveNoInteractions();
	}

	@Test
	void process_eventCompletedWhileWaitingForLock_returnsWithoutMutation() {
		Order order = createPaidOrderWithProducts();
		OrderProduct target = order.getOrderProducts().getFirst();
		UUID eventId = UUID.randomUUID();
		given(processedEventService.isProcessed(eventId, CONSUMER_GROUP)).willReturn(false, true);
		given(orderRepository.findByIdWithOrderProductsForUpdate(order.getId())).willReturn(Optional.of(order));

		processor.process(eventId, EVENT_TYPE, REFUNDED_AT, payload(order, target, "PARTIAL_REFUNDED"));

		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
		assertThat(target.getOrderStatus()).isEqualTo(OrderProductStatus.PAID);
		then(outboxEventAppender).shouldHaveNoInteractions();
		then(processedEventService).should(never()).markProcessed(any(), any(), any(), any());
	}

	@Test
	void process_differentEventIdForAlreadyRefundedProduct_marksProcessedWithoutOutbox() {
		Order order = createPaidOrderWithProducts();
		OrderProduct target = order.getOrderProducts().getFirst();
		order.refundOrderProduct(target.getId(), target.getProductAmount(), REFUNDED_AT.minusMinutes(1));
		UUID eventId = UUID.randomUUID();
		stubTarget(eventId, order);

		processor.process(eventId, EVENT_TYPE, REFUNDED_AT, payload(order, target, "PARTIAL_REFUNDED"));

		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PARTIAL_REFUNDED);
		assertThat(target.getRefundedAt()).isEqualTo(REFUNDED_AT.minusMinutes(1));
		then(outboxEventAppender).shouldHaveNoInteractions();
		then(processedEventService).should()
			.markProcessed(eventId, CONSUMER_GROUP, EVENT_TYPE, REFUNDED_AT);
	}

	@Test
	void process_buyerMismatch_rejectsWithoutMutationOrProcessedEvent() {
		Order order = createPaidOrderWithProducts();
		OrderProduct target = order.getOrderProducts().getFirst();
		UUID eventId = UUID.randomUUID();
		stubTarget(eventId, order);
		PaymentRefundedPayload payload = new PaymentRefundedPayload(
			PAYMENT_ID,
			order.getId(),
			UUID.randomUUID(),
			target.getId(),
			target.getProductAmount(),
			"PARTIAL_REFUNDED",
			REFUNDED_AT_OFFSET
		);

		assertThatThrownBy(() -> processor.process(eventId, EVENT_TYPE, REFUNDED_AT, payload))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_ACCESS_DENIED);
		assertUnchanged(order);
	}

	@Test
	void process_missingProduct_rejectsWithoutMutationOrProcessedEvent() {
		Order order = createPaidOrderWithProducts();
		UUID eventId = UUID.randomUUID();
		stubTarget(eventId, order);
		PaymentRefundedPayload payload = new PaymentRefundedPayload(
			PAYMENT_ID,
			order.getId(),
			BUYER_ID,
			UUID.randomUUID(),
			PRODUCT_AMOUNT_1,
			"PARTIAL_REFUNDED",
			REFUNDED_AT_OFFSET
		);

		assertThatThrownBy(() -> processor.process(eventId, EVENT_TYPE, REFUNDED_AT, payload))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_PRODUCT_NOT_FOUND);
		assertUnchanged(order);
	}

	@Test
	void process_amountMismatch_rejectsWithoutMutationOrProcessedEvent() {
		Order order = createPaidOrderWithProducts();
		OrderProduct target = order.getOrderProducts().getFirst();
		UUID eventId = UUID.randomUUID();
		stubTarget(eventId, order);
		PaymentRefundedPayload payload = new PaymentRefundedPayload(
			PAYMENT_ID,
			order.getId(),
			BUYER_ID,
			target.getId(),
			target.getProductAmount() - 1,
			"PARTIAL_REFUNDED",
			REFUNDED_AT_OFFSET
		);

		assertThatThrownBy(() -> processor.process(eventId, EVENT_TYPE, REFUNDED_AT, payload))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_REFUND_AMOUNT_MISMATCH);
		assertUnchanged(order);
	}

	@Test
	void process_nonPaidProduct_rejectsWithoutProcessedEvent() {
		Order order = createPendingOrderWithProducts();
		OrderProduct target = order.getOrderProducts().getFirst();
		UUID eventId = UUID.randomUUID();
		stubTarget(eventId, order);

		assertThatThrownBy(() -> processor.process(
			eventId,
			EVENT_TYPE,
			REFUNDED_AT,
			payload(order, target, "PARTIAL_REFUNDED")
		))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_ORDER_STATUS_TRANSITION);
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CREATED);
		then(processedEventService).should(never()).markProcessed(any(), any(), any(), any());
	}

	@Test
	void process_missingOrder_throwsBeforeMutation() {
		Order order = createPaidOrderWithProducts();
		OrderProduct target = order.getOrderProducts().getFirst();
		UUID eventId = UUID.randomUUID();
		given(processedEventService.isProcessed(eventId, CONSUMER_GROUP)).willReturn(false);
		given(orderRepository.findByIdWithOrderProductsForUpdate(order.getId())).willReturn(Optional.empty());

		assertThatThrownBy(() -> processor.process(
			eventId,
			EVENT_TYPE,
			REFUNDED_AT,
			payload(order, target, "PARTIAL_REFUNDED")
		))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_NOT_FOUND);
		then(outboxEventAppender).shouldHaveNoInteractions();
	}

	private PaymentRefundedPayload payload(Order order, OrderProduct product, String paymentStatus) {
		return new PaymentRefundedPayload(
			PAYMENT_ID,
			order.getId(),
			order.getBuyerId(),
			product.getId(),
			product.getProductAmount(),
			paymentStatus,
			REFUNDED_AT_OFFSET
		);
	}

	private void stubTarget(UUID eventId, Order order) {
		given(processedEventService.isProcessed(eventId, CONSUMER_GROUP)).willReturn(false);
		given(orderRepository.findByIdWithOrderProductsForUpdate(order.getId())).willReturn(Optional.of(order));
	}

	private void stubOrderRefundMessage() {
		given(orderEventMessageFactory.createOrderRefundMessage(any(), any()))
			.willAnswer(invocation -> {
				UUID orderId = invocation.getArgument(0);
				OrderRefundPayload payload = invocation.getArgument(1);
				return new EventMessage<>(
					UUID.randomUUID(),
					"ORDER_REFUND",
					REFUNDED_AT,
					"ORDER",
					orderId,
					payload
				);
			});
	}

	private void assertUnchanged(Order order) {
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
		assertThat(order.getOrderProducts())
			.extracting(OrderProduct::getOrderStatus)
			.containsOnly(OrderProductStatus.PAID);
		then(outboxEventAppender).shouldHaveNoInteractions();
		then(processedEventService).should(never()).markProcessed(any(), any(), any(), any());
	}
}
