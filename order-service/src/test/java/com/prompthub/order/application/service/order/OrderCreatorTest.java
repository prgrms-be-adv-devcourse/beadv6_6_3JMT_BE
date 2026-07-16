package com.prompthub.order.application.service.order;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.application.dto.CreateOrderResult;
import com.prompthub.order.application.dto.OrderItem;
import com.prompthub.order.application.event.order.OrderCreatedEvent;
import com.prompthub.order.application.service.event.OrderEventMessageFactory;
import com.prompthub.order.application.service.event.outbox.OutboxEventAppender;
import com.prompthub.order.domain.enums.OrderProductStatus;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.infra.messaging.kafka.event.OrderCreatedPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.prompthub.order.fixture.OrderV2Fixture.AMOUNT_A1;
import static com.prompthub.order.fixture.OrderV2Fixture.AMOUNT_A2;
import static com.prompthub.order.fixture.OrderV2Fixture.AMOUNT_B1;
import static com.prompthub.order.fixture.OrderV2Fixture.AMOUNT_C1;
import static com.prompthub.order.fixture.OrderV2Fixture.BUYER_ID;
import static com.prompthub.order.fixture.OrderV2Fixture.CREATED_AT;
import static com.prompthub.order.fixture.OrderV2Fixture.ORDER_GROUP_ID;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class OrderCreatorTest {

	@Mock
	private OrderRepository orderRepository;

	@Mock
	private OrderNumberGenerator orderNumberGenerator;

	@Mock
	private OrderEventMessageFactory orderEventMessageFactory;

	@Mock
	private OutboxEventAppender outboxEventAppender;

	@Mock
	private ApplicationEventPublisher applicationEventPublisher;

	@InjectMocks
	private OrderCreator orderCreator;

	@BeforeEach
	void setUp() {
		given(orderRepository.saveAll(anyList())).willAnswer(invocation -> {
			List<Order> orders = invocation.getArgument(0);
			orders.forEach(order -> {
				ReflectionTestUtils.setField(order, "createdAt", CREATED_AT);
				ReflectionTestUtils.setField(order, "updatedAt", CREATED_AT);
			});
			return orders;
		});
		given(orderEventMessageFactory.createOrderCreatedMessage(any(OrderCreatedPayload.class)))
			.willReturn(new EventMessage<>(
				ORDER_GROUP_ID,
				"ORDER_CREATED",
				CREATED_AT,
				"ORDER_GROUP",
				ORDER_GROUP_ID,
				null
			));
	}

	@Test
	@DisplayName("A·B·C 판매자 상품을 주문 세 건과 Outbox 한 건으로 생성한다")
	@SuppressWarnings("unchecked")
	void createsOrdersPerSellerAndSingleOutbox() {
		given(orderNumberGenerator.generate()).willReturn("ORD-A", "ORD-B", "ORD-C");

		CreateOrderResult result = orderCreator.create(BUYER_ID, orderItems());

		ArgumentCaptor<List<Order>> ordersCaptor = ArgumentCaptor.forClass(List.class);
		then(orderRepository).should().saveAll(ordersCaptor.capture());
		List<Order> orders = ordersCaptor.getValue();

		assertThat(orders).hasSize(3);
		assertThat(orders).extracting(Order::getSellerId)
			.containsExactly(SELLER_A, SELLER_B, SELLER_C);
		assertThat(orders).extracting(Order::getOrderNumber)
			.containsExactly("ORD-A", "ORD-B", "ORD-C")
			.doesNotHaveDuplicates();
		assertThat(orders).extracting(Order::getOrderStatus)
			.containsOnly(OrderStatus.CREATED);
		assertThat(orders).flatExtracting(Order::getOrderProducts)
			.hasSize(4)
			.extracting(OrderProduct::getOrderStatus)
			.containsOnly(OrderProductStatus.PENDING);

		Map<UUID, Order> bySeller = orders.stream()
			.collect(Collectors.toMap(Order::getSellerId, Function.identity()));
		assertSellerAOrder(bySeller.get(SELLER_A));
		assertSingleProductOrder(bySeller.get(SELLER_B), PRODUCT_B1, REQUEST_TITLE_B1, AMOUNT_B1);
		assertSingleProductOrder(bySeller.get(SELLER_C), PRODUCT_C1, REQUEST_TITLE_C1, AMOUNT_C1);

		assertThat(result.totalAmount()).isEqualTo(TOTAL_AMOUNT);
		assertThat(result.orders()).hasSize(3);
		assertThat(result.orders()).extracting(CreateOrderResult.Order::sellerId)
			.containsExactly(SELLER_A, SELLER_B, SELLER_C);
		then(orderNumberGenerator).should(times(3)).generate();
		then(outboxEventAppender).should(times(1)).append(any(EventMessage.class));
	}

	@Test
	@DisplayName("ORDER_CREATED payload는 생성된 모든 주문과 상품 및 합계를 포함한다")
	void outboxPayloadContainsAllOrdersAndProducts() {
		given(orderNumberGenerator.generate()).willReturn("ORD-A", "ORD-B", "ORD-C");

		orderCreator.create(BUYER_ID, orderItems());

		ArgumentCaptor<OrderCreatedPayload> payloadCaptor = ArgumentCaptor.forClass(OrderCreatedPayload.class);
		then(orderEventMessageFactory).should().createOrderCreatedMessage(payloadCaptor.capture());
		OrderCreatedPayload payload = payloadCaptor.getValue();

		assertThat(payload.buyerId()).isEqualTo(BUYER_ID);
		assertThat(payload.totalAmount()).isEqualTo(TOTAL_AMOUNT);
		assertThat(payload.orders()).hasSize(3);
		assertThat(payload.orders()).flatExtracting(OrderCreatedPayload.Order::products)
			.hasSize(4)
			.extracting(OrderCreatedPayload.Product::productId)
			.containsExactlyInAnyOrder(PRODUCT_A1, PRODUCT_A2, PRODUCT_B1, PRODUCT_C1);
		assertThat(payload.orders()).flatExtracting(OrderCreatedPayload.Order::products)
			.extracting(OrderCreatedPayload.Product::productTitle)
			.containsExactlyInAnyOrder(
				REQUEST_TITLE_A1,
				REQUEST_TITLE_A2,
				REQUEST_TITLE_B1,
				REQUEST_TITLE_C1
			);
	}

	@Test
	@DisplayName("생성된 모든 주문을 하나의 내부 이벤트로 만료 등록 흐름에 전달한다")
	void publishesCreatedOrdersForExpiration() {
		given(orderNumberGenerator.generate()).willReturn("ORD-A", "ORD-B", "ORD-C");

		orderCreator.create(BUYER_ID, orderItems());

		ArgumentCaptor<OrderCreatedEvent> eventCaptor = ArgumentCaptor.forClass(OrderCreatedEvent.class);
		then(applicationEventPublisher).should().publishEvent(eventCaptor.capture());
		assertThat(eventCaptor.getValue().orders())
			.hasSize(3)
			.extracting(OrderCreatedEvent.Item::createdAt)
			.containsOnly(CREATED_AT);
		assertThat(eventCaptor.getValue().orders())
			.extracting(OrderCreatedEvent.Item::orderId)
			.doesNotHaveDuplicates();
	}

	@Test
	@DisplayName("판매자가 한 명이어도 주문 한 건과 Outbox 한 건을 생성한다")
	void singleSellerStillCreatesSingleOutbox() {
		given(orderNumberGenerator.generate()).willReturn("ORD-A");
		List<OrderItem> singleSellerItems = List.of(
			new OrderItem(PRODUCT_A1, SELLER_A, REQUEST_TITLE_A1, AMOUNT_A1),
			new OrderItem(PRODUCT_A2, SELLER_A, REQUEST_TITLE_A2, AMOUNT_A2)
		);

		CreateOrderResult result = orderCreator.create(BUYER_ID, singleSellerItems);

		assertThat(result.orders()).hasSize(1);
		assertThat(result.orders().getFirst().products()).hasSize(2);
		then(orderRepository).should().saveAll(anyList());
		then(outboxEventAppender).should(times(1)).append(any(EventMessage.class));
	}

	private void assertSellerAOrder(Order order) {
		assertThat(order.getTotalOrderAmount()).isEqualTo(AMOUNT_A1 + AMOUNT_A2);
		assertThat(order.getOrderProducts())
			.extracting(OrderProduct::getProductId)
			.containsExactly(PRODUCT_A1, PRODUCT_A2);
		assertThat(order.getOrderProducts())
			.extracting(OrderProduct::getProductTitle)
			.containsExactly(REQUEST_TITLE_A1, REQUEST_TITLE_A2);
		assertThat(order.getOrderProducts())
			.allSatisfy(product -> assertThat(product.getOrder()).isSameAs(order));
	}

	private void assertSingleProductOrder(
		Order order,
		UUID productId,
		String title,
		int amount
	) {
		assertThat(order.getTotalOrderAmount()).isEqualTo(amount);
		assertThat(order.getOrderProducts()).singleElement()
			.satisfies(product -> {
				assertThat(product.getProductId()).isEqualTo(productId);
				assertThat(product.getProductTitle()).isEqualTo(title);
				assertThat(product.getProductAmount()).isEqualTo(amount);
				assertThat(product.getOrder()).isSameAs(order);
			});
	}
}
