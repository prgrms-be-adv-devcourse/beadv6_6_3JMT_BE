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

import static com.prompthub.order.fixture.OrderV2Fixture.AMOUNT_A1;
import static com.prompthub.order.fixture.OrderV2Fixture.AMOUNT_A2;
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
		given(orderRepository.save(any(Order.class))).willAnswer(invocation -> {
			Order order = invocation.getArgument(0);
			ReflectionTestUtils.setField(order, "createdAt", CREATED_AT);
			ReflectionTestUtils.setField(order, "updatedAt", CREATED_AT);
			return order;
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
	@DisplayName("A·B·C 판매자 상품을 주문 한 건과 Outbox 한 건으로 생성한다")
	void createsSingleOrderAndSingleOutbox() {
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
		then(outboxEventAppender).should(times(1)).append(any(EventMessage.class));
	}

	@Test
	@DisplayName("ORDER_CREATED payload는 생성된 단일 주문의 Payment Service 계약 필드를 포함한다")
	void outboxPayloadContainsSingleOrderPaymentServiceContract() {
		given(orderNumberGenerator.generate()).willReturn("ORD-A");

		orderCreator.create(BUYER_ID, orderItems());

		ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
		then(orderRepository).should().save(orderCaptor.capture());
		ArgumentCaptor<OrderCreatedPayload> payloadCaptor = ArgumentCaptor.forClass(OrderCreatedPayload.class);
		then(orderEventMessageFactory).should().createOrderCreatedMessage(payloadCaptor.capture());
		OrderCreatedPayload payload = payloadCaptor.getValue();

		assertThat(payload.orderId()).isEqualTo(orderCaptor.getValue().getId());
		assertThat(payload.buyerId()).isEqualTo(BUYER_ID);
		assertThat(payload.totalAmount()).isEqualTo(TOTAL_AMOUNT);
		assertThat(payload.createdAt()).isEqualTo(CREATED_AT);
	}

	@Test
	@DisplayName("생성된 단일 주문을 하나의 내부 이벤트로 만료 등록 흐름에 전달한다")
	void publishesCreatedOrderForExpiration() {
		given(orderNumberGenerator.generate()).willReturn("ORD-A");

		orderCreator.create(BUYER_ID, orderItems());

		ArgumentCaptor<OrderCreatedEvent> eventCaptor = ArgumentCaptor.forClass(OrderCreatedEvent.class);
		then(applicationEventPublisher).should().publishEvent(eventCaptor.capture());
		assertThat(eventCaptor.getValue().createdAt()).isEqualTo(CREATED_AT);
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

		assertThat(result.order().products()).hasSize(2);
		then(orderRepository).should().save(any(Order.class));
		then(outboxEventAppender).should(times(1)).append(any(EventMessage.class));
	}
}
