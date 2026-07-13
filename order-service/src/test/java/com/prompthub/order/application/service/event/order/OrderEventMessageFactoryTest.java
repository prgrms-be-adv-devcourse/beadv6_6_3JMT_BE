package com.prompthub.order.application.service.event.order;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.infra.messaging.kafka.event.OrderCreatedPayload;
import com.prompthub.order.infra.messaging.kafka.event.OrderPaidPayload;
import com.prompthub.order.infra.messaging.kafka.event.OrderRefundPayload;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrderEventMessageFactoryTest {

	private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
	private static final UUID BUYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000102");
	private static final LocalDateTime EVENT_TIME = LocalDateTime.of(2026, 7, 13, 15, 30);

	private final OrderEventMessageFactory factory = new OrderEventMessageFactory();

	@Test
	void createOrderCreatedMessage_usesOrderAggregateAndCurrentTime() {
		OrderCreatedPayload payload = new OrderCreatedPayload(
			ORDER_ID, BUYER_ID, "ORD-001", 1000, "PENDING", EVENT_TIME
		);
		LocalDateTime before = LocalDateTime.now();

		EventMessage<OrderCreatedPayload> message = factory.createOrderCreatedMessage(ORDER_ID, payload);

		assertCurrentEnvelope(message, "ORDER_CREATED", before, "ORDER", ORDER_ID, payload);
	}

	@Test
	void createOrderPaidMessage_usesOrderAggregateAndCurrentTime() {
		OrderPaidPayload payload = new OrderPaidPayload(ORDER_ID, BUYER_ID, 1000, 0, EVENT_TIME, java.util.List.of());
		LocalDateTime before = LocalDateTime.now();

		EventMessage<OrderPaidPayload> message = factory.createOrderPaidMessage(ORDER_ID, payload);

		assertCurrentEnvelope(message, "ORDER_PAID", before, "ORDER", ORDER_ID, payload);
	}

	@Test
	void createOrderRefundMessage_usesOrderAggregateAndCurrentTime() {
		OrderRefundPayload payload = new OrderRefundPayload(ORDER_ID, BUYER_ID, 1000, EVENT_TIME, java.util.List.of());
		LocalDateTime before = LocalDateTime.now();

		EventMessage<OrderRefundPayload> message = factory.createOrderRefundMessage(ORDER_ID, payload);

		assertCurrentEnvelope(message, "ORDER_REFUND", before, "ORDER", ORDER_ID, payload);
	}

	private <T> void assertCurrentEnvelope(
		EventMessage<T> message,
		String eventType,
		LocalDateTime before,
		String aggregateType,
		UUID aggregateId,
		T payload
	) {
		assertThat(message.occurredAt()).isBetween(before, LocalDateTime.now());
		assertThat(message.eventId()).isNotNull();
		assertThat(message.eventType()).isEqualTo(eventType);
		assertThat(message.aggregateType()).isEqualTo(aggregateType);
		assertThat(message.aggregateId()).isEqualTo(aggregateId);
		assertThat(message.payload()).isSameAs(payload);
	}
}
