package com.prompthub.order.infra.messaging.kafka.event;

import com.prompthub.order.domain.model.Order;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static com.prompthub.order.fixture.OrderV2Fixture.AMOUNT_A1;
import static com.prompthub.order.fixture.OrderV2Fixture.AMOUNT_A2;
import static com.prompthub.order.fixture.OrderV2Fixture.BUYER_ID;
import static com.prompthub.order.fixture.OrderV2Fixture.CREATED_AT;
import static com.prompthub.order.fixture.OrderV2Fixture.ORDER_A;
import static org.assertj.core.api.Assertions.assertThat;

class OrderCreatedPayloadSerializationTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	@DisplayName("ORDER_CREATED payload JSON은 Payment Service 단건 계약 필드만 포함한다")
	void serializesSingleOrderPaymentServiceContract() throws Exception {
		Order order = order(ORDER_A, "ORD-A", AMOUNT_A1 + AMOUNT_A2);
		OrderCreatedPayload payload = OrderCreatedPayload.from(order);

		@SuppressWarnings("unchecked")
		Map<String, Object> actual = objectMapper.readValue(objectMapper.writeValueAsString(payload), Map.class);

		assertThat(actual).containsOnlyKeys("orderId", "buyerId", "totalAmount", "createdAt");
		assertThat(actual).doesNotContainKeys("orders", "products", "sellerId", "orderNumber", "orderStatus");
	}

	private Order order(java.util.UUID id, String number, int amount) {
		Order order = Order.create(BUYER_ID, number, amount);
		ReflectionTestUtils.setField(order, "id", id);
		ReflectionTestUtils.setField(order, "createdAt", CREATED_AT);
		ReflectionTestUtils.setField(order, "updatedAt", CREATED_AT);
		return order;
	}

}
