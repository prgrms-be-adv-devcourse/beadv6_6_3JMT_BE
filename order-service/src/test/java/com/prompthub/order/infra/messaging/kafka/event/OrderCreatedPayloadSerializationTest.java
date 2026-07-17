package com.prompthub.order.infra.messaging.kafka.event;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.List;

import static com.prompthub.order.fixture.OrderV2Fixture.AMOUNT_A1;
import static com.prompthub.order.fixture.OrderV2Fixture.AMOUNT_A2;
import static com.prompthub.order.fixture.OrderV2Fixture.AMOUNT_B1;
import static com.prompthub.order.fixture.OrderV2Fixture.AMOUNT_C1;
import static com.prompthub.order.fixture.OrderV2Fixture.BUYER_ID;
import static com.prompthub.order.fixture.OrderV2Fixture.CREATED_AT;
import static com.prompthub.order.fixture.OrderV2Fixture.ORDER_A;
import static com.prompthub.order.fixture.OrderV2Fixture.ORDER_B;
import static com.prompthub.order.fixture.OrderV2Fixture.ORDER_C;
import static com.prompthub.order.fixture.OrderV2Fixture.ORDER_GROUP_ID;
import static com.prompthub.order.fixture.OrderV2Fixture.ORDER_PRODUCT_A1;
import static com.prompthub.order.fixture.OrderV2Fixture.ORDER_PRODUCT_A2;
import static com.prompthub.order.fixture.OrderV2Fixture.ORDER_PRODUCT_B1;
import static com.prompthub.order.fixture.OrderV2Fixture.ORDER_PRODUCT_C1;
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
import static org.assertj.core.api.Assertions.assertThat;

class OrderCreatedPayloadSerializationTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	@DisplayName("다건 ORDER_CREATED JSON은 모든 주문과 상품 및 합계를 포함한다")
	void serializesMultiOrderContract() throws Exception {
		List<Order> orders = orders();
		OrderCreatedPayload payload = OrderCreatedPayload.from(BUYER_ID, orders);
		EventMessage<OrderCreatedPayload> message = new EventMessage<>(
			ORDER_GROUP_ID,
			"ORDER_CREATED",
			CREATED_AT,
			"ORDER_GROUP",
			ORDER_GROUP_ID,
			payload
		);

		JsonNode actual = objectMapper.readTree(objectMapper.writeValueAsString(message));
		try (InputStream input = getClass().getResourceAsStream("/contracts/order-created-v2.json")) {
			assertThat(input).isNotNull();
			JsonNode expected = objectMapper.readTree(input);
			assertThat(actual).isEqualTo(expected);
		}

		assertThat(payload.totalAmount()).isEqualTo(TOTAL_AMOUNT);
		assertThat(payload.orders()).extracting(OrderCreatedPayload.Order::totalAmount)
			.containsExactly(AMOUNT_A1 + AMOUNT_A2, AMOUNT_B1, AMOUNT_C1);
		assertThat(payload.orders()).flatExtracting(OrderCreatedPayload.Order::products)
			.extracting(OrderCreatedPayload.Product::productAmount)
			.containsExactlyInAnyOrder(AMOUNT_A1, AMOUNT_A2, AMOUNT_B1, AMOUNT_C1);
		assertThat(payload.orders()).allSatisfy(item -> assertThat(item).isNotInstanceOf(Order.class));
	}

	private List<Order> orders() {
		Order orderA = order(ORDER_A, "ORD-A", AMOUNT_A1 + AMOUNT_A2);
		orderA.addOrderProduct(product(ORDER_PRODUCT_A1, PRODUCT_A1, SELLER_A, REQUEST_TITLE_A1, AMOUNT_A1));
		orderA.addOrderProduct(product(ORDER_PRODUCT_A2, PRODUCT_A2, SELLER_A, REQUEST_TITLE_A2, AMOUNT_A2));

		Order orderB = order(ORDER_B, "ORD-B", AMOUNT_B1);
		orderB.addOrderProduct(product(ORDER_PRODUCT_B1, PRODUCT_B1, SELLER_B, REQUEST_TITLE_B1, AMOUNT_B1));

		Order orderC = order(ORDER_C, "ORD-C", AMOUNT_C1);
		orderC.addOrderProduct(product(ORDER_PRODUCT_C1, PRODUCT_C1, SELLER_C, REQUEST_TITLE_C1, AMOUNT_C1));

		return List.of(orderA, orderB, orderC);
	}

	private Order order(java.util.UUID id, String number, int amount) {
		Order order = Order.create(BUYER_ID, number, amount);
		ReflectionTestUtils.setField(order, "id", id);
		ReflectionTestUtils.setField(order, "createdAt", CREATED_AT);
		ReflectionTestUtils.setField(order, "updatedAt", CREATED_AT);
		return order;
	}

	private OrderProduct product(
		java.util.UUID id,
		java.util.UUID productId,
		java.util.UUID sellerId,
		String title,
		int amount
	) {
		OrderProduct product = OrderProduct.create(productId, sellerId, title, amount);
		ReflectionTestUtils.setField(product, "id", id);
		ReflectionTestUtils.setField(product, "createdAt", CREATED_AT);
		ReflectionTestUtils.setField(product, "updatedAt", CREATED_AT);
		return product;
	}
}
