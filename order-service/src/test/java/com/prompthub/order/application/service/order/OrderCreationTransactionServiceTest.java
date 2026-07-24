package com.prompthub.order.application.service.order;

import com.prompthub.order.application.dto.CreateOrderResult;
import com.prompthub.order.application.event.order.OrderCreatedEvent;
import com.prompthub.order.application.event.order.OrderProductReservationCleanupEvent;
import com.prompthub.order.application.service.event.OrderPaidOutboxAppender;
import com.prompthub.order.domain.model.Cart;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.repository.CartRepository;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.prompthub.order.fixture.OrderV2Fixture.AMOUNT_A1;
import static com.prompthub.order.fixture.OrderV2Fixture.AMOUNT_A2;
import static com.prompthub.order.fixture.OrderV2Fixture.BUYER_ID;
import static com.prompthub.order.fixture.OrderV2Fixture.CREATED_AT;
import static com.prompthub.order.fixture.OrderV2Fixture.PRODUCT_A1;
import static com.prompthub.order.fixture.OrderV2Fixture.PRODUCT_A2;
import static com.prompthub.order.fixture.OrderV2Fixture.PRODUCT_B1;
import static com.prompthub.order.fixture.OrderV2Fixture.REQUEST_TITLE_A1;
import static com.prompthub.order.fixture.OrderV2Fixture.REQUEST_TITLE_A2;
import static com.prompthub.order.fixture.OrderV2Fixture.REQUEST_TITLE_B1;
import static com.prompthub.order.fixture.OrderV2Fixture.SELLER_A;
import static com.prompthub.order.fixture.OrderV2Fixture.SELLER_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class OrderCreationTransactionServiceTest {

	private static final UUID UNRELATED_PRODUCT =
		UUID.fromString("00000000-0000-0000-0000-000000000205");

	@Mock
	private OrderRepository orderRepository;

	@Mock
	private CartRepository cartRepository;

	@Mock
	private ApplicationEventPublisher applicationEventPublisher;

	@Mock
	private OrderPaidOutboxAppender orderPaidOutboxAppender;

	@Mock
	private OrderProductPurchasePolicy purchasePolicy;

	@InjectMocks
	private OrderCreationTransactionService service;

	@Test
	@DisplayName("DB 구매 정책 재검증 후 주문을 flush 저장한다")
	void create_rechecksPurchasePolicyBeforeSaveAndFlush() {
		Order order = paidOrder();
		stubSave(order);

		CreateOrderResult result = service.create(order);

		assertThat(result.totalAmount()).isEqualTo(order.getTotalOrderAmount());
		InOrder ordered = inOrder(purchasePolicy, orderRepository);
		ordered.verify(purchasePolicy).validateOrderable(
			BUYER_ID,
			List.of(PRODUCT_A1, PRODUCT_A2, PRODUCT_B1)
		);
		ordered.verify(orderRepository).saveAndFlush(order);
	}

	@Test
	@DisplayName("저장된 주문 상품만 장바구니에서 제거한다")
	void create_removesOnlyOrderedProductsFromCart() {
		Order order = paidOrder();
		stubSave(order);
		Cart cart = Cart.create(BUYER_ID);
		cart.addProduct(PRODUCT_A1);
		cart.addProduct(PRODUCT_B1);
		cart.addProduct(UNRELATED_PRODUCT);
		given(cartRepository.findByBuyerIdForUpdateWithCartProducts(BUYER_ID))
			.willReturn(Optional.of(cart));

		service.create(order);

		assertThat(cart.getCartProducts())
			.extracting(product -> product.getProductId())
			.containsExactly(UNRELATED_PRODUCT);
		InOrder ordered = inOrder(orderRepository, cartRepository);
		ordered.verify(orderRepository).saveAndFlush(order);
		ordered.verify(cartRepository).findByBuyerIdForUpdateWithCartProducts(BUYER_ID);
		ordered.verify(cartRepository).save(cart);
	}

	@Test
	@DisplayName("유료 주문은 생성 이벤트를 발행한다")
	void create_paidOrderPublishesOrderCreatedEvent() {
		Order order = paidOrder();
		stubSave(order);

		service.create(order);

		ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
		then(applicationEventPublisher).should().publishEvent(eventCaptor.capture());
		assertThat(eventCaptor.getValue()).isInstanceOfSatisfying(
			OrderCreatedEvent.class,
			event -> {
				assertThat(event.orderId()).isEqualTo(order.getId());
				assertThat(event.createdAt()).isEqualTo(CREATED_AT);
			}
		);
		then(orderPaidOutboxAppender).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("무료 주문은 ORDER_PAID Outbox와 예약 정리 이벤트를 반영한다")
	void create_freeOrderAppendsPaidOutboxAndPublishesReservationCleanup() {
		Order order = freeOrder();
		stubSave(order);

		service.create(order);

		then(orderPaidOutboxAppender).should().append(order);
		then(applicationEventPublisher).should().publishEvent(
			OrderProductReservationCleanupEvent.from(order)
		);
		then(applicationEventPublisher).should(never())
			.publishEvent(any(OrderCreatedEvent.class));
	}

	@Test
	@DisplayName("장바구니 저장 실패는 원래 예외로 전파한다")
	void create_cartSaveFailurePreservesOriginalFailure() {
		Order order = paidOrder();
		stubSave(order);
		Cart cart = Cart.create(BUYER_ID);
		cart.addProduct(PRODUCT_A1);
		given(cartRepository.findByBuyerIdForUpdateWithCartProducts(BUYER_ID))
			.willReturn(Optional.of(cart));
		RuntimeException failure = new RuntimeException("cart save failure");
		willThrow(failure).given(cartRepository).save(cart);

		assertThatThrownBy(() -> service.create(order))
			.isSameAs(failure);

		then(applicationEventPublisher).shouldHaveNoInteractions();
		then(orderPaidOutboxAppender).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("DB 구매 정책이 차단하면 저장·장바구니·이벤트 부수효과가 없다")
	void create_purchasePolicyFailureHasNoDatabaseOrEventSideEffects() {
		Order order = paidOrder();
		OrderException failure = new OrderException(ErrorCode.ORDER_PRODUCT_ALREADY_OWNED);
		willThrow(failure).given(purchasePolicy).validateOrderable(
			eq(BUYER_ID),
			eq(List.of(PRODUCT_A1, PRODUCT_A2, PRODUCT_B1))
		);

		assertThatThrownBy(() -> service.create(order))
			.isSameAs(failure);

		then(orderRepository).shouldHaveNoInteractions();
		then(cartRepository).shouldHaveNoInteractions();
		then(applicationEventPublisher).shouldHaveNoInteractions();
		then(orderPaidOutboxAppender).shouldHaveNoInteractions();
	}

	private Order paidOrder() {
		Order order = Order.create(BUYER_ID, "ORD-A", AMOUNT_A1 + AMOUNT_A2 + 3_300);
		order.addOrderProduct(OrderProduct.create(
			PRODUCT_B1, SELLER_B, REQUEST_TITLE_B1, 3_300
		));
		order.addOrderProduct(OrderProduct.create(
			PRODUCT_A1, SELLER_A, REQUEST_TITLE_A1, AMOUNT_A1
		));
		order.addOrderProduct(OrderProduct.create(
			PRODUCT_A2, SELLER_A, REQUEST_TITLE_A2, AMOUNT_A2
		));
		setAuditTimes(order);
		return order;
	}

	private Order freeOrder() {
		Order order = Order.create(BUYER_ID, "ORD-FREE", 0);
		order.addOrderProduct(OrderProduct.create(
			PRODUCT_A1, SELLER_A, REQUEST_TITLE_A1, 0
		));
		order.completeFreeOrder();
		setAuditTimes(order);
		return order;
	}

	private void setAuditTimes(Order order) {
		ReflectionTestUtils.setField(order, "createdAt", CREATED_AT);
		ReflectionTestUtils.setField(order, "updatedAt", CREATED_AT);
	}

	private void stubSave(Order order) {
		given(orderRepository.saveAndFlush(order)).willReturn(order);
	}
}
