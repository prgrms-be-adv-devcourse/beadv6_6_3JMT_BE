package com.prompthub.order.domain.model;

import com.prompthub.order.domain.enums.OutboxEventStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.prompthub.order.fixture.OrderFixture.APPROVED_AT;
import static com.prompthub.order.fixture.OrderFixture.ORDER_ID;
import static org.assertj.core.api.Assertions.assertThat;

class OutboxEventTest {

	@Test
	@DisplayName("ORDER_PAID Outbox 이벤트는 주문 이벤트 토픽과 PENDING 상태로 생성된다")
	void orderPaid_createsPendingOutboxEvent() {
		String payload = "{\"orderId\":\"%s\"}".formatted(ORDER_ID);

		OutboxEvent outboxEvent = OutboxEvent.orderPaid(ORDER_ID, payload, APPROVED_AT);

		assertThat(outboxEvent.getId()).isInstanceOf(UUID.class);
		assertThat(outboxEvent.getAggregateId()).isEqualTo(ORDER_ID);
		assertThat(outboxEvent.getAggregateType()).isEqualTo("ORDER");
		assertThat(outboxEvent.getEventType()).isEqualTo("ORDER_PAID");
		assertThat(outboxEvent.getTopic()).isEqualTo("order-events");
		assertThat(outboxEvent.getPayload()).isEqualTo(payload);
		assertThat(outboxEvent.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
		assertThat(outboxEvent.getRetryCount()).isZero();
		assertThat(outboxEvent.getOccurredAt()).isEqualTo(APPROVED_AT);
		assertThat(outboxEvent.getPublishedAt()).isNull();
	}
}
