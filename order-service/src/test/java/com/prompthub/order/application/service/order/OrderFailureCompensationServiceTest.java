package com.prompthub.order.application.service.order;

import com.prompthub.order.application.event.order.OrderExpirationCleanupRequestedEvent;
import com.prompthub.order.application.service.event.ProcessedEventService;
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
import com.prompthub.order.infra.messaging.kafka.event.PaymentFailedPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.prompthub.order.fixture.PaymentEventFixture.APPROVED_AT;
import static com.prompthub.order.fixture.PaymentEventFixture.BUYER_ID;
import static com.prompthub.order.fixture.PaymentEventFixture.FAILED_AT;
import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_A;
import static com.prompthub.order.fixture.PaymentEventFixture.OTHER_BUYER_ID;
import static com.prompthub.order.fixture.PaymentEventFixture.PAYMENT_ID;
import static com.prompthub.order.fixture.PaymentEventFixture.PRODUCT_A;
import static com.prompthub.order.fixture.PaymentEventFixture.PRODUCT_B;
import static com.prompthub.order.fixture.PaymentEventFixture.SELLER_A;
import static com.prompthub.order.fixture.PaymentEventFixture.createdOrder;
import static com.prompthub.order.fixture.PaymentEventFixture.failedPayload;
import static com.prompthub.order.fixture.PaymentEventFixture.productIds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class OrderFailureCompensationServiceTest {

	private static final String EVENT_TYPE = "PAYMENT_FAILED";
	private static final String CONSUMER_GROUP = "order-service";
	private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000901");
	private static final UUID UNRELATED_PRODUCT =
		UUID.fromString("00000000-0000-0000-0000-000000000999");

	@Mock
	private OrderRepository orderRepository;

	@Mock
	private CartRepository cartRepository;

	@Mock
	private ProcessedEventService processedEventService;

	@Mock
	private OrderExpirationPolicy expirationPolicy;

	@Mock
	private ApplicationEventPublisher eventPublisher;

	@InjectMocks
	private OrderFailureCompensationService service;

	@Test
	@DisplayName("결제 실패 보상은 Order 루트를 잠근 뒤 Cart 루트를 잠근다")
	void compensatePaymentFailure_locksOrderBeforeCart() {
		Order order = createdOrder();
		stubUnprocessedOrder(order);
		given(cartRepository.findByBuyerIdForUpdateWithCartProducts(BUYER_ID))
			.willReturn(Optional.of(Cart.create(BUYER_ID)));

		service.compensatePaymentFailure(EVENT_ID, EVENT_TYPE, FAILED_AT, failedPayload());

		InOrder lockOrder = inOrder(orderRepository, cartRepository);
		lockOrder.verify(orderRepository).findByIdWithOrderProductsForUpdate(ORDER_A);
		lockOrder.verify(cartRepository).findByBuyerIdForUpdateWithCartProducts(BUYER_ID);
	}

	@Test
	@DisplayName("CREATED 주문과 장바구니가 없으면 주문 상품 네 건을 복구하고 실패 처리한다")
	void compensatePaymentFailure_createdOrderWithoutCart_restoresAllProductsAndMarksProcessed() {
		Order order = createdOrder();
		stubUnprocessedOrder(order);
		given(cartRepository.findByBuyerIdForUpdateWithCartProducts(BUYER_ID))
			.willReturn(Optional.empty());

		service.compensatePaymentFailure(EVENT_ID, EVENT_TYPE, FAILED_AT, failedPayload());

		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.FAILED);
		assertThat(order.getOrderProducts())
			.extracting(OrderProduct::getOrderStatus)
			.containsOnly(OrderProductStatus.FAILED);
		ArgumentCaptor<Cart> cartCaptor = ArgumentCaptor.forClass(Cart.class);
		then(cartRepository).should().save(cartCaptor.capture());
		assertThat(cartCaptor.getValue().getBuyerId()).isEqualTo(BUYER_ID);
		assertThat(cartCaptor.getValue().getCartProducts())
			.extracting(CartProduct::getProductId)
			.containsExactlyInAnyOrderElementsOf(productIds());
		then(processedEventService).should()
			.markProcessed(EVENT_ID, CONSUMER_GROUP, EVENT_TYPE, FAILED_AT);
		assertCleanupPublished(ORDER_A);
	}

	@Test
	@DisplayName("기존 동일 상품은 유지하고 없는 주문 상품만 장바구니에 추가한다")
	void compensatePaymentFailure_existingCart_addsOnlyMissingProducts() {
		Order order = createdOrder();
		Cart cart = Cart.create(BUYER_ID);
		cart.addProduct(PRODUCT_A);
		cart.addProduct(UNRELATED_PRODUCT);
		stubUnprocessedOrder(order);
		given(cartRepository.findByBuyerIdForUpdateWithCartProducts(BUYER_ID))
			.willReturn(Optional.of(cart));

		service.compensatePaymentFailure(EVENT_ID, EVENT_TYPE, FAILED_AT, failedPayload());

		assertThat(cart.getCartProducts())
			.extracting(CartProduct::getProductId)
			.containsExactlyInAnyOrder(
				PRODUCT_A,
				PRODUCT_B,
				productIds().get(2),
				productIds().get(3),
				UNRELATED_PRODUCT
			);
		then(cartRepository).should().save(cart);
	}

	@Test
	@DisplayName("바로 구매 단건 실패도 새 장바구니에 상품 한 건을 복구한다")
	void compensatePaymentFailure_directPurchase_restoresSingleProduct() {
		Order order = Order.create(BUYER_ID, "ORD-DIRECT", 10_000);
		order.addOrderProduct(OrderProduct.create(PRODUCT_A, SELLER_A, "단건 상품", 10_000));
		PaymentFailedPayload payload = new PaymentFailedPayload(PAYMENT_ID, order.getId(), BUYER_ID);
		given(processedEventService.isProcessed(EVENT_ID, CONSUMER_GROUP)).willReturn(false);
		given(orderRepository.findByIdWithOrderProductsForUpdate(order.getId()))
			.willReturn(Optional.of(order));
		given(cartRepository.findByBuyerIdForUpdateWithCartProducts(BUYER_ID))
			.willReturn(Optional.empty());

		service.compensatePaymentFailure(EVENT_ID, EVENT_TYPE, FAILED_AT, payload);

		ArgumentCaptor<Cart> cartCaptor = ArgumentCaptor.forClass(Cart.class);
		then(cartRepository).should().save(cartCaptor.capture());
		assertThat(cartCaptor.getValue().getCartProducts())
			.extracting(CartProduct::getProductId)
			.containsExactly(PRODUCT_A);
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.FAILED);
	}

	@Test
	@DisplayName("식별자 필드가 없는 축소형 실패 이벤트도 주문 소유 정보로 보상한다")
	void compensatePaymentFailure_reducedPaymentContract_restoresProducts() {
		Order order = createdOrder();
		PaymentFailedPayload payload = new PaymentFailedPayload(
			null,
			ORDER_A,
			null,
			order.getTotalOrderAmount(),
			null,
			null,
			"2026-07-17T01:00:05Z"
		);
		stubUnprocessedOrder(order);
		given(cartRepository.findByBuyerIdForUpdateWithCartProducts(BUYER_ID))
			.willReturn(Optional.empty());

		service.compensatePaymentFailure(EVENT_ID, EVENT_TYPE, FAILED_AT, payload);

		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.FAILED);
		then(cartRepository).should().save(any(Cart.class));
	}

	@Test
	@DisplayName("축소형 실패 이벤트의 금액이 주문 금액과 다르면 보상을 거부한다")
	void compensatePaymentFailure_reducedPaymentContractRejectsAmountMismatch() {
		Order order = createdOrder();
		PaymentFailedPayload payload = new PaymentFailedPayload(
			null,
			ORDER_A,
			null,
			order.getTotalOrderAmount() - 1,
			null,
			null,
			"2026-07-17T01:00:05Z"
		);
		stubUnprocessedOrder(order);

		assertThatThrownBy(() -> service.compensatePaymentFailure(
			EVENT_ID, EVENT_TYPE, FAILED_AT, payload
		))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_PAYMENT_AMOUNT_MISMATCH);

		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CREATED);
		then(cartRepository).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("이미 처리한 eventId면 Order와 Cart를 조회하지 않고 Redis 정리만 요청한다")
	void compensatePaymentFailure_duplicateBeforeLock_publishesCleanupOnly() {
		given(processedEventService.isProcessed(EVENT_ID, CONSUMER_GROUP)).willReturn(true);

		service.compensatePaymentFailure(EVENT_ID, EVENT_TYPE, FAILED_AT, failedPayload());

		then(orderRepository).shouldHaveNoInteractions();
		then(cartRepository).shouldHaveNoInteractions();
		then(processedEventService).should(never()).markProcessed(any(), any(), any(), any());
		assertCleanupPublished(ORDER_A);
	}

	@Test
	@DisplayName("Order 잠금을 기다리는 동안 eventId가 처리되면 상태와 Cart를 변경하지 않는다")
	void compensatePaymentFailure_duplicateAfterLock_doesNotMutateStateOrCart() {
		Order order = createdOrder();
		given(processedEventService.isProcessed(EVENT_ID, CONSUMER_GROUP)).willReturn(false, true);
		given(orderRepository.findByIdWithOrderProductsForUpdate(ORDER_A)).willReturn(Optional.of(order));

		service.compensatePaymentFailure(EVENT_ID, EVENT_TYPE, FAILED_AT, failedPayload());

		InOrder processingOrder = inOrder(processedEventService, orderRepository);
		processingOrder.verify(processedEventService).isProcessed(EVENT_ID, CONSUMER_GROUP);
		processingOrder.verify(orderRepository).findByIdWithOrderProductsForUpdate(ORDER_A);
		processingOrder.verify(processedEventService).isProcessed(EVENT_ID, CONSUMER_GROUP);
		then(processedEventService).should(times(2)).isProcessed(EVENT_ID, CONSUMER_GROUP);
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CREATED);
		then(cartRepository).shouldHaveNoInteractions();
		then(processedEventService).should(never()).markProcessed(any(), any(), any(), any());
		assertCleanupPublished(ORDER_A);
	}

	@ParameterizedTest
	@EnumSource(value = OrderStatus.class, names = {"FAILED", "COMPLETED", "PARTIAL_REFUNDED", "ALL_REFUNDED"})
	@DisplayName("CREATED가 아닌 주문의 최초 늦은 실패는 상태와 Cart를 변경하지 않고 처리 이력만 남긴다")
	void compensatePaymentFailure_nonCreatedOrder_isNoOpExceptProcessedEvent(OrderStatus status) {
		Order order = orderInStatus(status);
		stubUnprocessedOrder(order);

		service.compensatePaymentFailure(EVENT_ID, EVENT_TYPE, FAILED_AT, failedPayload());

		assertThat(order.getOrderStatus()).isEqualTo(status);
		then(cartRepository).shouldHaveNoInteractions();
		then(processedEventService).should()
			.markProcessed(EVENT_ID, CONSUMER_GROUP, EVENT_TYPE, FAILED_AT);
		assertCleanupPublished(ORDER_A);
	}

	@Test
	@DisplayName("payload 구매자가 주문 구매자와 다르면 보상과 처리 이력을 거부한다")
	void compensatePaymentFailure_buyerMismatch_rejectsWithoutMutation() {
		Order order = createdOrder();
		stubUnprocessedOrder(order);
		PaymentFailedPayload payload = new PaymentFailedPayload(PAYMENT_ID, ORDER_A, OTHER_BUYER_ID);

		assertThatThrownBy(() -> service.compensatePaymentFailure(EVENT_ID, EVENT_TYPE, FAILED_AT, payload))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_ACCESS_DENIED);

		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CREATED);
		then(cartRepository).shouldHaveNoInteractions();
		then(processedEventService).should(never()).markProcessed(any(), any(), any(), any());
		then(eventPublisher).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("Cart 저장 예외를 삼키지 않고 주문 실패 전이를 수행하지 않는다")
	void compensatePaymentFailure_cartSaveFailure_propagatesException() {
		Order order = createdOrder();
		RuntimeException failure = new RuntimeException("cart save failure");
		stubUnprocessedOrder(order);
		given(cartRepository.findByBuyerIdForUpdateWithCartProducts(BUYER_ID))
			.willReturn(Optional.empty());
		willThrow(failure).given(cartRepository).save(any(Cart.class));

		assertThatThrownBy(() -> service.compensatePaymentFailure(
			EVENT_ID, EVENT_TYPE, FAILED_AT, failedPayload()
		)).isSameAs(failure);

		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CREATED);
		then(processedEventService).should(never()).markProcessed(any(), any(), any(), any());
		then(eventPublisher).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("processed-event 저장 예외를 삼키지 않고 cleanup을 발행하지 않는다")
	void compensatePaymentFailure_processedEventSaveFailure_propagatesException() {
		Order order = createdOrder();
		RuntimeException failure = new RuntimeException("processed event save failure");
		stubUnprocessedOrder(order);
		given(cartRepository.findByBuyerIdForUpdateWithCartProducts(BUYER_ID))
			.willReturn(Optional.empty());
		willThrow(failure).given(processedEventService)
			.markProcessed(EVENT_ID, CONSUMER_GROUP, EVENT_TYPE, FAILED_AT);

		assertThatThrownBy(() -> service.compensatePaymentFailure(
			EVENT_ID, EVENT_TYPE, FAILED_AT, failedPayload()
		)).isSameAs(failure);

		then(eventPublisher).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("타임아웃 주문이 없으면 성공 처리하고 Redis 정리만 요청한다")
	void compensateTimeout_missingOrder_returnsTrueAndPublishesCleanup() {
		given(orderRepository.findByIdWithOrderProductsForUpdate(ORDER_A)).willReturn(Optional.empty());

		boolean compensated = service.compensateTimeout(ORDER_A, FAILED_AT);

		assertThat(compensated).isTrue();
		then(cartRepository).shouldHaveNoInteractions();
		then(processedEventService).shouldHaveNoInteractions();
		assertCleanupPublished(ORDER_A);
	}

	@Test
	@DisplayName("타임아웃 주문이 CREATED가 아니면 Cart를 변경하지 않고 정리만 요청한다")
	void compensateTimeout_nonCreatedOrder_returnsTrueWithoutCartMutation() {
		Order order = createdOrder();
		order.markCompleted(APPROVED_AT);
		given(orderRepository.findByIdWithOrderProductsForUpdate(ORDER_A)).willReturn(Optional.of(order));

		boolean compensated = service.compensateTimeout(ORDER_A, FAILED_AT);

		assertThat(compensated).isTrue();
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
		then(cartRepository).shouldHaveNoInteractions();
		then(processedEventService).shouldHaveNoInteractions();
		assertCleanupPublished(ORDER_A);
	}

	@Test
	@DisplayName("결제 제한 시간이 지나지 않은 CREATED 주문은 보상과 Redis 정리를 수행하지 않는다")
	void compensateTimeout_notExpiredCreatedOrder_returnsFalseWithoutMutation() {
		Order order = createdOrderAt(FAILED_AT.minusMinutes(19));
		given(orderRepository.findByIdWithOrderProductsForUpdate(ORDER_A)).willReturn(Optional.of(order));
		given(expirationPolicy.paymentTimeoutMinutes()).willReturn(20);

		boolean compensated = service.compensateTimeout(ORDER_A, FAILED_AT);

		assertThat(compensated).isFalse();
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CREATED);
		assertThat(order.getOrderProducts())
			.extracting(OrderProduct::getOrderStatus)
			.containsOnly(OrderProductStatus.PENDING);
		then(cartRepository).shouldHaveNoInteractions();
		then(processedEventService).shouldHaveNoInteractions();
		then(eventPublisher).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("CREATED 주문 타임아웃은 결제 실패와 같은 Cart 복구와 상태 전이를 사용한다")
	void compensateTimeout_createdOrder_restoresCartAndMarksFailed() {
		Order order = createdOrderAt(FAILED_AT.minusMinutes(20));
		given(orderRepository.findByIdWithOrderProductsForUpdate(ORDER_A)).willReturn(Optional.of(order));
		given(expirationPolicy.paymentTimeoutMinutes()).willReturn(20);
		given(cartRepository.findByBuyerIdForUpdateWithCartProducts(BUYER_ID))
			.willReturn(Optional.empty());

		boolean compensated = service.compensateTimeout(ORDER_A, FAILED_AT);

		assertThat(compensated).isTrue();
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.FAILED);
		ArgumentCaptor<Cart> cartCaptor = ArgumentCaptor.forClass(Cart.class);
		then(cartRepository).should().save(cartCaptor.capture());
		assertThat(cartCaptor.getValue().getCartProducts())
			.extracting(CartProduct::getProductId)
			.containsExactlyInAnyOrderElementsOf(productIds());
		then(processedEventService).shouldHaveNoInteractions();
		assertCleanupPublished(ORDER_A);
	}

	private Order createdOrderAt(LocalDateTime createdAt) {
		Order order = createdOrder();
		ReflectionTestUtils.setField(order, "createdAt", createdAt);
		return order;
	}

	private Order orderInStatus(OrderStatus status) {
		Order order = createdOrder();
		switch (status) {
			case FAILED -> order.markFailed(FAILED_AT);
			case COMPLETED -> order.markCompleted(APPROVED_AT);
			case PARTIAL_REFUNDED -> {
				order.markCompleted(APPROVED_AT);
				OrderProduct product = order.getOrderProducts().getFirst();
				order.refundOrderProduct(product.getId(), product.getProductAmount(), FAILED_AT);
			}
			case ALL_REFUNDED -> {
				order.markCompleted(APPROVED_AT);
				List.copyOf(order.getOrderProducts()).forEach(product ->
					order.refundOrderProduct(product.getId(), product.getProductAmount(), FAILED_AT)
				);
			}
			case CREATED -> throw new IllegalArgumentException("non-created status is required");
		}
		return order;
	}

	private void stubUnprocessedOrder(Order order) {
		given(processedEventService.isProcessed(EVENT_ID, CONSUMER_GROUP)).willReturn(false);
		given(orderRepository.findByIdWithOrderProductsForUpdate(order.getId()))
			.willReturn(Optional.of(order));
	}

	private void assertCleanupPublished(UUID orderId) {
		then(eventPublisher).should().publishEvent(new OrderExpirationCleanupRequestedEvent(orderId));
	}
}
