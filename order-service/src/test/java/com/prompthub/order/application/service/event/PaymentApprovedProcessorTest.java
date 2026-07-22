package com.prompthub.order.application.service.event;

import com.prompthub.order.application.event.order.OrderExpirationCleanupRequestedEvent;
import com.prompthub.order.domain.enums.OrderProductStatus;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Cart;
import com.prompthub.order.domain.model.CartProduct;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.repository.CartRepository;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.infra.messaging.kafka.event.PaymentApprovedPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.prompthub.order.fixture.PaymentEventFixture.APPROVED_AT;
import static com.prompthub.order.fixture.PaymentEventFixture.APPROVED_AT_OFFSET;
import static com.prompthub.order.fixture.PaymentEventFixture.BUYER_ID;
import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_A;
import static com.prompthub.order.fixture.PaymentEventFixture.OTHER_BUYER_ID;
import static com.prompthub.order.fixture.PaymentEventFixture.PAYMENT_ID;
import static com.prompthub.order.fixture.PaymentEventFixture.approvedPayload;
import static com.prompthub.order.fixture.PaymentEventFixture.createdOrder;
import static com.prompthub.order.fixture.PaymentEventFixture.productIds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class PaymentApprovedProcessorTest {

	private static final String EVENT_TYPE = "PAYMENT_APPROVED";
	private static final String CONSUMER_GROUP = "order-service";
	private static final UUID UNRELATED_PRODUCT =
		UUID.fromString("00000000-0000-0000-0000-000000000999");

	@Mock
	private ProcessedEventService processedEventService;

	@Mock
	private OrderRepository orderRepository;

	@Mock
	private CartRepository cartRepository;

	@Mock
	private OrderPaidOutboxAppender orderPaidOutboxAppender;

	@Mock
	private ApplicationEventPublisher applicationEventPublisher;

	@Spy
	private PaymentEventValidator validator = new PaymentEventValidator();

	@InjectMocks
	private PaymentApprovedProcessor processor;

	@Test
	@DisplayName("CREATED 승인 시 재추가된 주문 상품을 제거하고 무관한 Cart 상품은 유지한다")
	void process_createdOrder_completesProductsRemovesCartAndPersistsEventsInOrder() {
		Order order = createdOrder();
		Cart cart = compensatedCart();
		PaymentApprovedPayload payload = approvedPayload(order);
		UUID eventId = UUID.randomUUID();
		stubTarget(eventId, order);
		given(cartRepository.findByBuyerIdForUpdateWithCartProducts(BUYER_ID)).willReturn(Optional.of(cart));
		processor.process(eventId, EVENT_TYPE, APPROVED_AT, payload);

		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
		assertThat(order.getCompletedAt()).isEqualTo(APPROVED_AT);
		assertThat(order.getOrderProducts())
			.extracting(OrderProduct::getOrderStatus)
			.containsOnly(OrderProductStatus.PAID);
		assertThat(cart.getCartProducts())
			.extracting(CartProduct::getProductId)
			.containsExactly(UNRELATED_PRODUCT);

		then(orderPaidOutboxAppender).should().append(order);

		var processingOrder = org.mockito.Mockito.inOrder(
			processedEventService,
			orderRepository,
			cartRepository,
			orderPaidOutboxAppender,
			applicationEventPublisher
		);
		processingOrder.verify(processedEventService).isProcessed(eventId, CONSUMER_GROUP);
		processingOrder.verify(orderRepository).findByIdWithOrderProductsForUpdate(ORDER_A);
		processingOrder.verify(processedEventService).isProcessed(eventId, CONSUMER_GROUP);
		processingOrder.verify(cartRepository).findByBuyerIdForUpdateWithCartProducts(BUYER_ID);
		processingOrder.verify(cartRepository).save(cart);
		processingOrder.verify(orderPaidOutboxAppender).append(order);
		processingOrder.verify(processedEventService)
			.markProcessed(eventId, CONSUMER_GROUP, EVENT_TYPE, APPROVED_AT);
		processingOrder.verify(applicationEventPublisher)
			.publishEvent(new OrderExpirationCleanupRequestedEvent(ORDER_A));
	}

	@Test
	@DisplayName("보상된 FAILED 주문의 늦은 승인은 COMPLETED/PAID가 되고 복구 상품을 제거한다")
	void process_failedOrder_lateApprovalWinsAndRemovesRestoredProducts() {
		Order order = createdOrder();
		order.markFailed();
		Cart cart = compensatedCart();
		UUID eventId = UUID.randomUUID();
		stubTarget(eventId, order);
		given(cartRepository.findByBuyerIdForUpdateWithCartProducts(BUYER_ID)).willReturn(Optional.of(cart));
		processor.process(eventId, EVENT_TYPE, APPROVED_AT, approvedPayload(order));

		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
		assertThat(order.getOrderProducts())
			.extracting(OrderProduct::getOrderStatus)
			.containsOnly(OrderProductStatus.PAID);
		assertThat(cart.getCartProducts())
			.extracting(CartProduct::getProductId)
			.containsExactly(UNRELATED_PRODUCT);
		then(cartRepository).should().save(cart);
		then(applicationEventPublisher).should()
			.publishEvent(new OrderExpirationCleanupRequestedEvent(ORDER_A));
	}

	@Test
	@DisplayName("구매자의 Cart가 없어도 승인 상태·Outbox·처리 이력·cleanup을 반영한다")
	void process_withoutCart_completesWithoutCartSave() {
		Order order = createdOrder();
		UUID eventId = UUID.randomUUID();
		stubTarget(eventId, order);
		given(cartRepository.findByBuyerIdForUpdateWithCartProducts(BUYER_ID)).willReturn(Optional.empty());
		processor.process(eventId, EVENT_TYPE, APPROVED_AT, approvedPayload(order));

		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
		then(cartRepository).should(never()).save(any());
		then(orderPaidOutboxAppender).should().append(order);
		then(processedEventService).should()
			.markProcessed(eventId, CONSUMER_GROUP, EVENT_TYPE, APPROVED_AT);
		then(applicationEventPublisher).should()
			.publishEvent(new OrderExpirationCleanupRequestedEvent(ORDER_A));
	}

	@ParameterizedTest
	@EnumSource(value = OrderStatus.class, names = {"COMPLETED", "PARTIAL_REFUNDED", "ALL_REFUNDED"})
	@DisplayName("완료·환불 주문의 늦은 승인은 상태·Cart·Outbox를 바꾸지 않고 처리 이력과 cleanup만 남긴다")
	void process_lateApproval_isNoOpExceptProcessedEventAndCleanup(OrderStatus status) {
		Order order = orderInStatus(status);
		UUID eventId = UUID.randomUUID();
		stubTarget(eventId, order);

		processor.process(eventId, EVENT_TYPE, APPROVED_AT, approvedPayload(order));

		then(orderPaidOutboxAppender).shouldHaveNoInteractions();
		then(cartRepository).shouldHaveNoInteractions();
		then(processedEventService).should()
			.markProcessed(eventId, CONSUMER_GROUP, EVENT_TYPE, APPROVED_AT);
		then(applicationEventPublisher).should()
			.publishEvent(new OrderExpirationCleanupRequestedEvent(ORDER_A));
		assertThat(order.getOrderStatus()).isEqualTo(status);
	}

	@Test
	@DisplayName("구매자가 다르면 상태·Cart·Outbox·처리 이력·cleanup을 변경하지 않는다")
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

		assertThatThrownBy(() -> processor.process(eventId, EVENT_TYPE, APPROVED_AT, payload))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_ACCESS_DENIED);
		assertUnchanged(order);
	}

	@Test
	@DisplayName("승인 금액이 다르면 상태·Cart·Outbox·처리 이력·cleanup을 변경하지 않는다")
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

		assertThatThrownBy(() -> processor.process(eventId, EVENT_TYPE, APPROVED_AT, payload))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_PAYMENT_AMOUNT_MISMATCH);
		assertUnchanged(order);
	}

	@Test
	void process_missingOrder_throwsBeforeMutation() {
		Order order = createdOrder();
		UUID eventId = UUID.randomUUID();
		given(processedEventService.isProcessed(eventId, CONSUMER_GROUP)).willReturn(false);
		given(orderRepository.findByIdWithOrderProductsForUpdate(ORDER_A)).willReturn(Optional.empty());

		assertThatThrownBy(() -> processor.process(eventId, EVENT_TYPE, APPROVED_AT, approvedPayload(order)))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_NOT_FOUND);
		then(orderPaidOutboxAppender).shouldHaveNoInteractions();
		then(applicationEventPublisher).shouldHaveNoInteractions();
	}

	@Test
	void process_sameEventId_returnsBeforeLocking() {
		UUID eventId = UUID.randomUUID();
		given(processedEventService.isProcessed(eventId, CONSUMER_GROUP)).willReturn(true);

		processor.process(eventId, EVENT_TYPE, APPROVED_AT, approvedPayload(createdOrder()));

		then(orderRepository).shouldHaveNoInteractions();
		then(cartRepository).shouldHaveNoInteractions();
		then(orderPaidOutboxAppender).shouldHaveNoInteractions();
		then(applicationEventPublisher).should()
			.publishEvent(new OrderExpirationCleanupRequestedEvent(ORDER_A));
	}

	@Test
	void process_eventCompletedWhileWaitingForLock_returnsWithoutMutation() {
		Order order = createdOrder();
		UUID eventId = UUID.randomUUID();
		given(processedEventService.isProcessed(eventId, CONSUMER_GROUP)).willReturn(false, true);
		given(orderRepository.findByIdWithOrderProductsForUpdate(ORDER_A)).willReturn(Optional.of(order));

		processor.process(eventId, EVENT_TYPE, APPROVED_AT, approvedPayload(order));

		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CREATED);
		assertThat(order.getOrderProducts())
			.extracting(OrderProduct::getOrderStatus)
			.containsOnly(OrderProductStatus.PENDING);
		then(orderPaidOutboxAppender).shouldHaveNoInteractions();
		then(cartRepository).shouldHaveNoInteractions();
		then(processedEventService).should(never()).markProcessed(any(), any(), any(), any());
		then(applicationEventPublisher).should()
			.publishEvent(new OrderExpirationCleanupRequestedEvent(ORDER_A));
	}

	private Cart compensatedCart() {
		Cart cart = Cart.create(BUYER_ID);
		cart.addProductsIfAbsent(productIds());
		cart.addProduct(UNRELATED_PRODUCT);
		return cart;
	}

	private Order orderInStatus(OrderStatus status) {
		Order order = createdOrder();
		order.markCompleted(APPROVED_AT);
		if (status == OrderStatus.COMPLETED) {
			return order;
		}
		switch (status) {
			case PARTIAL_REFUNDED -> {
				OrderProduct product = order.getOrderProducts().getFirst();
				order.refundOrderProduct(
					product.getId(),
					product.getProductAmount(),
					APPROVED_AT.plusMinutes(1)
				);
			}
			case ALL_REFUNDED -> List.copyOf(order.getOrderProducts()).forEach(product ->
				order.refundOrderProduct(
					product.getId(),
					product.getProductAmount(),
					APPROVED_AT.plusMinutes(1)
				)
			);
			default -> throw new IllegalArgumentException("refunded status is required");
		}
		return order;
	}

	private void assertUnchanged(Order order) {
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CREATED);
		assertThat(order.getOrderProducts())
			.extracting(OrderProduct::getOrderStatus)
			.containsOnly(OrderProductStatus.PENDING);
		then(orderPaidOutboxAppender).shouldHaveNoInteractions();
		then(cartRepository).shouldHaveNoInteractions();
		then(applicationEventPublisher).shouldHaveNoInteractions();
		then(processedEventService).should(never()).markProcessed(any(), any(), any(), any());
	}

	private void stubTarget(UUID eventId, Order order) {
		given(processedEventService.isProcessed(eventId, CONSUMER_GROUP)).willReturn(false);
		given(orderRepository.findByIdWithOrderProductsForUpdate(ORDER_A)).willReturn(Optional.of(order));
	}

}
