package com.prompthub.order.application.service.event;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.infra.messaging.kafka.event.OrderCreatedPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.prompthub.order.fixture.OrderV2Fixture.BUYER_ID;
import static org.assertj.core.api.Assertions.assertThat;

class OrderEventMessageFactoryTest {

	@Test
	@DisplayName("ORDER_CREATED는 주문 묶음 ID를 eventId와 aggregateId로 함께 사용한다")
	void orderCreatedUsesSingleOrderGroupId() {
		OrderCreatedPayload payload = new OrderCreatedPayload(BUYER_ID, 0, List.of());
		OrderEventMessageFactory factory = new OrderEventMessageFactory();

		EventMessage<OrderCreatedPayload> message = factory.createOrderCreatedMessage(payload);

		assertThat(message.eventId()).isNotNull();
		assertThat(message.aggregateId()).isEqualTo(message.eventId());
		assertThat(message.eventType()).isEqualTo("ORDER_CREATED");
		assertThat(message.aggregateType()).isEqualTo("ORDER_GROUP");
		assertThat(message.payload()).isSameAs(payload);
		assertThat(message.occurredAt()).isNotNull();
	}
}
