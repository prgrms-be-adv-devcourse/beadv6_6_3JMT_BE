package com.prompthub.order.application.service.order;

import com.prompthub.order.application.dto.CreateOrderResult;
import com.prompthub.order.application.dto.OrderItem;
import com.prompthub.order.application.event.order.OrderCreatedEvent;
import com.prompthub.order.application.event.order.OrderProductReservationCleanupRequestedEvent;
import com.prompthub.order.application.service.event.OrderPaidOutboxAppender;
import com.prompthub.order.domain.enums.OrderProductStatus;
import com.prompthub.order.domain.enums.OrderStatus;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.prompthub.order.fixture.OrderV2Fixture.AMOUNT_A1;
import static com.prompthub.order.fixture.OrderV2Fixture.AMOUNT_A2;
import static com.prompthub.order.fixture.OrderV2Fixture.BUYER_ID;
import static com.prompthub.order.fixture.OrderV2Fixture.CREATED_AT;
import static com.prompthub.order.fixture.OrderV2Fixture.ORDER_A;
import static com.prompthub.order.fixture.OrderV2Fixture.PRODUCT_A1;
import static com.prompthub.order.fixture.OrderV2Fixture.PRODUCT_A2;
import static com.prompthub.order.fixture.OrderV2Fixture.PRODUCT_B1;
import static com.prompthub.order.fixture.OrderV2Fixture.PRODUCT_C1;
import static com.prompthub.order.fixture.OrderV2Fixture.REQUEST_TITLE_A1;
import static com.prompthub.order.fixture.OrderV2Fixture.REQUEST_TITLE_A2;
import static com.prompthub.order.fixture.OrderV2Fixture.REQUEST_TITLE_B1;
import static com.prompthub.order.fixture.OrderV2Fixture.REQUEST_TITLE_C1;
import static com.prompthub.order.fixture.OrderV2Fixture.SELLER_A;
import static com.prompthub.order.fixture.OrderV2Fixture.SELLER_B;
import static com.prompthub.order.fixture.OrderV2Fixture.SELLER_C;
import static com.prompthub.order.fixture.OrderV2Fixture.TOTAL_AMOUNT;
import static com.prompthub.order.fixture.OrderV2Fixture.orderItems;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class OrderCreatorTest {

	private static final UUID UNRELATED_PRODUCT =
		UUID.fromString("00000000-0000-0000-0000-000000000205");

	@Mock
	private OrderRepository orderRepository;

	@Mock
	private CartRepository cartRepository;

	@Mock
	private OrderNumberGenerator orderNumberGenerator;

	@Mock
	private ApplicationEventPublisher applicationEventPublisher;

	@Mock
	private OrderPaidOutboxAppender orderPaidOutboxAppender;

	@Mock
	private OrderProductPurchasePolicy purchasePolicy;

	@Mock
	private OrderProductReservationService reservationService;

	@InjectMocks
	private OrderCreator orderCreator;

	private void stubSuccessfulCreation() {
		given(orderRepository.save(any(Order.class))).willAnswer(invocation -> {
			Order order = invocation.getArgument(0);
			ReflectionTestUtils.setField(order, "createdAt", CREATED_AT);
			ReflectionTestUtils.setField(order, "updatedAt", CREATED_AT);
			return order;
		});
	}

	@Test
	@DisplayName("A·B·C 판매자 상품을 주문 한 건으로 생성한다")
	void createsSingleOrderWithMultipleSellerProducts() {
		stubSuccessfulCreation();
		given(orderNumberGenerator.generate()).willReturn("ORD-A");

		CreateOrderResult result = orderCreator.create(BUYER_ID, orderItems());

		ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
		then(orderRepository).should().save(orderCaptor.capture());
		Order order = orderCaptor.getValue();

		assertThat(order.getOrderProducts())
			.extracting(OrderProduct::getSellerId)
			.containsExactly(SELLER_A, SELLER_B, SELLER_A, SELLER_C);
		assertThat(order.getOrderNumber()).isEqualTo("ORD-A");
		assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CREATED);
		assertThat(order.getOrderProducts())
			.hasSize(4)
			.extracting(OrderProduct::getOrderStatus)
			.containsOnly(OrderProductStatus.PENDING);
		assertThat(order.getTotalOrderAmount()).isEqualTo(TOTAL_AMOUNT);
		assertThat(order.getOrderProducts())
			.extracting(OrderProduct::getProductId)
			.containsExactly(PRODUCT_A1, PRODUCT_B1, PRODUCT_A2, PRODUCT_C1);
		assertThat(order.getOrderProducts())
			.extracting(OrderProduct::getProductTitle)
			.containsExactly(REQUEST_TITLE_A1, REQUEST_TITLE_B1, REQUEST_TITLE_A2, REQUEST_TITLE_C1);
		assertThat(order.getOrderProducts())
			.allSatisfy(product -> assertThat(product.getOrder()).isSameAs(order));

		assertThat(result.totalAmount()).isEqualTo(TOTAL_AMOUNT);
		assertThat(result.order().products())
			.extracting(CreateOrderResult.Product::sellerId)
			.containsExactly(SELLER_A, SELLER_B, SELLER_A, SELLER_C);
		then(orderNumberGenerator).should(times(1)).generate();
		then(orderRepository).should(times(1)).save(any(Order.class));
	}

	@Test
	@DisplayName("주문 생성 시 주문 상품만 장바구니에서 제거하고 무관한 상품은 유지한다")
	void removesOrderedProductsFromExistingCart() {
		stubSuccessfulCreation();
		given(orderNumberGenerator.generate()).willReturn("ORD-A");
		Cart cart = Cart.create(BUYER_ID);
		cart.addProduct(PRODUCT_A1);
		cart.addProduct(PRODUCT_B1);
		cart.addProduct(UNRELATED_PRODUCT);
		given(cartRepository.findByBuyerIdForUpdateWithCartProducts(BUYER_ID))
			.willReturn(Optional.of(cart));

		orderCreator.create(BUYER_ID, orderItems());

		assertThat(cart.getCartProducts())
			.extracting(product -> product.getProductId())
			.containsExactly(UNRELATED_PRODUCT);

		InOrder lockOrder = inOrder(orderRepository, cartRepository);
		lockOrder.verify(orderRepository).save(any(Order.class));
		lockOrder.verify(cartRepository).findByBuyerIdForUpdateWithCartProducts(BUYER_ID);
		lockOrder.verify(cartRepository).save(cart);
	}

	@Test
	@DisplayName("주문 생성 시 장바구니가 없으면 장바구니 저장을 시도하지 않는다")
	void doesNotSaveCartWhenBuyerHasNoCart() {
		stubSuccessfulCreation();
		given(orderNumberGenerator.generate()).willReturn("ORD-A");
		given(cartRepository.findByBuyerIdForUpdateWithCartProducts(BUYER_ID))
			.willReturn(Optional.empty());

		orderCreator.create(BUYER_ID, orderItems());

		then(cartRepository).should().findByBuyerIdForUpdateWithCartProducts(BUYER_ID);
		then(cartRepository).shouldHaveNoMoreInteractions();
	}

	@Test
	@DisplayName("생성된 단일 주문을 하나의 내부 이벤트로 만료 등록 흐름에 전달한다")
	void publishesCreatedOrderForExpiration() {
		stubSuccessfulCreation();
		given(orderNumberGenerator.generate()).willReturn("ORD-A");

		orderCreator.create(BUYER_ID, orderItems());

		ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
		then(orderRepository).should().save(orderCaptor.capture());
		ArgumentCaptor<OrderCreatedEvent> eventCaptor = ArgumentCaptor.forClass(OrderCreatedEvent.class);
		then(applicationEventPublisher).should().publishEvent(eventCaptor.capture());
		assertThat(eventCaptor.getValue().orderId()).isEqualTo(orderCaptor.getValue().getId());
		assertThat(eventCaptor.getValue().createdAt()).isEqualTo(CREATED_AT);
	}

	@Test
	@DisplayName("판매자가 한 명이어도 주문 한 건을 생성한다")
	void singleSellerStillCreatesSingleOrder() {
		stubSuccessfulCreation();
		given(orderNumberGenerator.generate()).willReturn("ORD-A");
		List<OrderItem> singleSellerItems = List.of(
			new OrderItem(PRODUCT_A1, SELLER_A, REQUEST_TITLE_A1, AMOUNT_A1),
			new OrderItem(PRODUCT_A2, SELLER_A, REQUEST_TITLE_A2, AMOUNT_A2)
		);

		CreateOrderResult result = orderCreator.create(BUYER_ID, singleSellerItems);

		assertThat(result.order().products()).hasSize(2);
		then(orderRepository).should().save(any(Order.class));
	}

	@Test
	@DisplayName("상품 총액이 int 최댓값이면 음수로 뒤집히지 않고 주문을 생성한다")
	void totalAmountAtIntMax_createsOrder() {
		stubSuccessfulCreation();
		given(orderNumberGenerator.generate()).willReturn("ORD-MAX");
		List<OrderItem> items = List.of(
			new OrderItem(PRODUCT_A1, SELLER_A, REQUEST_TITLE_A1, Integer.MAX_VALUE - 1),
			new OrderItem(PRODUCT_A2, SELLER_A, REQUEST_TITLE_A2, 1)
		);

		CreateOrderResult result = orderCreator.create(BUYER_ID, items);

		assertThat(result.totalAmount()).isEqualTo(Integer.MAX_VALUE);
	}

	@Test
	@DisplayName("상품 총액 overflow면 안정적인 예외를 반환하고 외부 부수효과를 만들지 않는다")
	void totalAmountOverflow_throwsStableOrderExceptionWithoutSideEffects() {
		List<OrderItem> items = List.of(
			new OrderItem(PRODUCT_A1, SELLER_A, REQUEST_TITLE_A1, Integer.MAX_VALUE),
			new OrderItem(PRODUCT_A2, SELLER_A, REQUEST_TITLE_A2, 1)
		);

		assertThatThrownBy(() -> orderCreator.create(BUYER_ID, items))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT_VALUE);

		then(orderNumberGenerator).shouldHaveNoInteractions();
		then(orderRepository).shouldHaveNoInteractions();
		then(cartRepository).shouldHaveNoInteractions();
		then(applicationEventPublisher).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("0원 주문은 즉시 완료하고 ORDER_PAID Outbox와 예약 정리를 반영한다")
	void freeOrder_completesAndAppendsPaidOutboxWithoutExpirationEvent() {
		stubSuccessfulCreation();
		given(orderNumberGenerator.generate()).willReturn("ORD-FREE");
		List<OrderItem> items = List.of(
			new OrderItem(PRODUCT_A1, SELLER_A, REQUEST_TITLE_A1, 0)
		);

		CreateOrderResult result = orderCreator.create(BUYER_ID, items);

		ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
		then(orderRepository).should().save(orderCaptor.capture());
		Order saved = orderCaptor.getValue();
		assertThat(result.totalAmount()).isZero();
		assertThat(saved.getOrderStatus()).isEqualTo(OrderStatus.COMPLETED);
		assertThat(saved.getCompletedAt()).isNotNull();
		assertThat(saved.getOrderProducts()).extracting(OrderProduct::getOrderStatus)
			.containsOnly(OrderProductStatus.PAID);
		then(orderPaidOutboxAppender).should().append(saved);
		then(applicationEventPublisher).should()
			.publishEvent(OrderProductReservationCleanupRequestedEvent.from(saved));
	}

	@Test
	@DisplayName("이미 접근 가능한 무료 상품은 O018로 거부하고 부수효과를 만들지 않는다")
	void duplicateAccessibleFreeProduct_throwsConflictWithoutSideEffects() {
		willThrow(new OrderException(ErrorCode.ORDER_PRODUCT_ALREADY_OWNED))
			.given(purchasePolicy).validateOrderable(eq(BUYER_ID), anyList());
		List<OrderItem> items = List.of(new OrderItem(PRODUCT_A1, SELLER_A, REQUEST_TITLE_A1, 0));

		assertThatThrownBy(() -> orderCreator.create(BUYER_ID, items))
			.isInstanceOf(OrderException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_PRODUCT_ALREADY_OWNED);

		then(orderNumberGenerator).shouldHaveNoInteractions();
		then(cartRepository).shouldHaveNoInteractions();
		then(orderPaidOutboxAppender).shouldHaveNoInteractions();
		then(applicationEventPublisher).shouldHaveNoInteractions();
	}
}
