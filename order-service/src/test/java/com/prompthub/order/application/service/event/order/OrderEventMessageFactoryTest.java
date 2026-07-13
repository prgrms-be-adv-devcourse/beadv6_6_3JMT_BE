package com.prompthub.order.application.service.event.order;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.infra.messaging.kafka.event.OrderCreatedPayload;
import com.prompthub.order.infra.messaging.kafka.event.OrderPaidPayload;
import com.prompthub.order.infra.messaging.kafka.event.OrderProductRefundedPayload;
import com.prompthub.order.infra.messaging.kafka.event.OrderRefundPayload;
import com.prompthub.order.infra.messaging.kafka.event.RefundRequestedPayload;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrderEventMessageFactoryTest {

	private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
	private static final UUID BUYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000102");
	private static final UUID PAYMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000103");
	private static final UUID REFUND_ID = UUID.fromString("00000000-0000-0000-0000-000000000104");
	private static final LocalDateTime EVENT_TIME = LocalDateTime.of(2026, 7, 13, 15, 30);

	private final OrderEventMessageFactory factory = new OrderEventMessageFactory();

	@Test
	void createRefundRequestedMessage_usesRefundRequestAggregateAndRequestedAt() {
		var product = new RefundRequestedPayload.RefundRequestedProductPayload(ORDER_ID, PAYMENT_ID, 1000);
		RefundRequestedPayload payload = new RefundRequestedPayload(
			REFUND_ID, PAYMENT_ID, ORDER_ID, BUYER_ID, 1000, null, List.of(product), EVENT_TIME
		);

		EventMessage<RefundRequestedPayload> message = factory.createRefundRequestedMessage(REFUND_ID, payload);

		assertEnvelope(message, "REFUND_REQUESTED", EVENT_TIME, "REFUND_REQUEST", REFUND_ID, payload);
		assertThat(message.payload().products()).containsExactly(product);
	}

	@Test
	void createOrderProductRefundedMessage_usesOrderAggregateAndRefundedAt() {
		var product = new OrderProductRefundedPayload.OrderRefundedProductPayload(
			ORDER_ID, PAYMENT_ID, BUYER_ID, 1000
		);
		OrderProductRefundedPayload payload = new OrderProductRefundedPayload(
			REFUND_ID, ORDER_ID, BUYER_ID, 1000, EVENT_TIME, List.of(product)
		);

		EventMessage<OrderProductRefundedPayload> message =
			factory.createOrderProductRefundedMessage(ORDER_ID, payload);

		assertEnvelope(message, "ORDER_REFUNDED", EVENT_TIME, "ORDER", ORDER_ID, payload);
		assertThat(message.payload().products()).containsExactly(product);
	}

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
		OrderPaidPayload payload = new OrderPaidPayload(ORDER_ID, BUYER_ID, 1000, 0, EVENT_TIME, List.of());
		LocalDateTime before = LocalDateTime.now();

		EventMessage<OrderPaidPayload> message = factory.createOrderPaidMessage(ORDER_ID, payload);

		assertCurrentEnvelope(message, "ORDER_PAID", before, "ORDER", ORDER_ID, payload);
	}

	@Test
	void createOrderRefundMessage_usesOrderAggregateAndCurrentTime() {
		OrderRefundPayload payload = new OrderRefundPayload(ORDER_ID, BUYER_ID, 1000, EVENT_TIME, List.of());
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
		assertEnvelopeFields(message, eventType, aggregateType, aggregateId, payload);
	}

	private <T> void assertEnvelope(
		EventMessage<T> message,
		String eventType,
		LocalDateTime occurredAt,
		String aggregateType,
		UUID aggregateId,
		T payload
	) {
		assertThat(message.occurredAt()).isEqualTo(occurredAt);
		assertEnvelopeFields(message, eventType, aggregateType, aggregateId, payload);
	}

	private <T> void assertEnvelopeFields(
		EventMessage<T> message,
		String eventType,
		String aggregateType,
		UUID aggregateId,
		T payload
	) {
		assertThat(message.eventId()).isNotNull();
		assertThat(message.eventType()).isEqualTo(eventType);
		assertThat(message.aggregateType()).isEqualTo(aggregateType);
		assertThat(message.aggregateId()).isEqualTo(aggregateId);
		assertThat(message.payload()).isSameAs(payload);
	}
}
