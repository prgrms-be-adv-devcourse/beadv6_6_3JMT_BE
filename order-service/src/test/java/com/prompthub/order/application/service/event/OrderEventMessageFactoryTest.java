package com.prompthub.order.application.service.event;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.infra.messaging.kafka.event.OrderCreatedPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.prompthub.order.fixture.OrderV2Fixture.BUYER_ID;
import static com.prompthub.order.fixture.OrderV2Fixture.CREATED_AT;
import static com.prompthub.order.fixture.OrderV2Fixture.ORDER_A;
import static org.assertj.core.api.Assertions.assertThat;

class OrderEventMessageFactoryTest {

	@Test
	@DisplayName("ORDER_CREATED는 단건 주문 ID를 ORDER aggregate로 사용한다")
	void orderCreatedUsesSingleOrderIdAsAggregateId() {
		OrderCreatedPayload payload = new OrderCreatedPayload(
			ORDER_A,
			BUYER_ID,
			0,
			CREATED_AT
		);
		OrderEventMessageFactory factory = new OrderEventMessageFactory();

		EventMessage<OrderCreatedPayload> message = factory.createOrderCreatedMessage(payload);

		assertThat(message.eventId()).isNotNull();
		assertThat(message.aggregateId()).isEqualTo(ORDER_A);
		assertThat(message.eventType()).isEqualTo("ORDER_CREATED");
		assertThat(message.aggregateType()).isEqualTo("ORDER");
		assertThat(message.payload()).isSameAs(payload);
		assertThat(message.occurredAt()).isNotNull();
	}
}
