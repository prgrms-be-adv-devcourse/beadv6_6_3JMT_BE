package com.prompthub.common.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EventMessageTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	@DisplayName("TC-COMMON-004: 공통 팩토리가 EventMessage envelope를 생성한다")
	void create_eventMessage_envelope() {
		UUID aggregateId = UUID.randomUUID();
		LocalDateTime occurredAt = LocalDateTime.of(2026, 7, 13, 15, 0);
		OrderPaidPayload payload = new OrderPaidPayload(aggregateId, 30000);

		EventMessage<OrderPaidPayload> message = EventMessage.create(
			TestEventType.ORDER_PAID,
			occurredAt,
			"ORDER",
			aggregateId,
			payload
		);

		assertThat(message.eventId()).isNotNull();
		assertThat(message.eventType()).isEqualTo("ORDER_PAID");
		assertThat(message.occurredAt()).isEqualTo(occurredAt);
		assertThat(message.aggregateType()).isEqualTo("ORDER");
		assertThat(message.aggregateId()).isEqualTo(aggregateId);
		assertThat(message.payload()).isSameAs(payload);
	}

	@Test
	@DisplayName("TC-COMMON-001: EventMessage가 정상 JSON으로 직렬화된다")
	void serialize_eventMessage_to_json() throws Exception {
		// Given
		UUID eventId = UUID.randomUUID();
		UUID aggregateId = UUID.randomUUID();
		LocalDateTime occurredAt = LocalDateTime.of(2026, 7, 8, 11, 30, 0);
		OrderPaidPayload payload = new OrderPaidPayload(aggregateId, 30000);

		EventMessage<OrderPaidPayload> message = new EventMessage<>(
			eventId,
			"ORDER_PAID",
			occurredAt,
			"ORDER",
			aggregateId,
			payload
		);

		// When
		String json = objectMapper.writeValueAsString(message);
		JsonNode root = objectMapper.readTree(json);

		// Then
		assertThat(root.path("eventId").stringValue()).isEqualTo(eventId.toString());
		assertThat(root.path("eventType").stringValue()).isEqualTo("ORDER_PAID");
		// Jackson 기본 직렬화 설정에 따라 LocalDateTime은 배열이거나 문자열일 수 있음. 여기선 존재 여부만 확인.
		assertThat(root.has("occurredAt")).isTrue();
		assertThat(root.path("aggregateType").stringValue()).isEqualTo("ORDER");
		assertThat(root.path("aggregateId").stringValue()).isEqualTo(aggregateId.toString());

		// TC-COMMON-003: payload 필드명이 유지된다
		JsonNode payloadNode = root.path("payload");
		assertThat(payloadNode.isMissingNode()).isFalse();
		assertThat(payloadNode.path("orderId").stringValue()).isEqualTo(aggregateId.toString());
		assertThat(payloadNode.path("amount").intValue()).isEqualTo(30000);
	}

	@Test
	@DisplayName("TC-COMMON-002: EventMessage<JsonNode>로 역직렬화된다")
	void deserialize_json_to_eventMessage_with_jsonNode() throws Exception {
		// Given
		UUID eventId = UUID.randomUUID();
		UUID aggregateId = UUID.randomUUID();
		String json = """
			{
			  "eventId": "%s",
			  "eventType": "PAYMENT_APPROVED",
			  "occurredAt": [2026, 7, 8, 11, 30],
			  "aggregateType": "ORDER",
			  "aggregateId": "%s",
			  "payload": {
			    "paymentId": "payment-123",
			    "amount": 30000
			  }
			}
			""".formatted(eventId, aggregateId);

		// When
		EventMessage<JsonNode> eventMessage = objectMapper.readValue(
			json,
			new tools.jackson.core.type.TypeReference<EventMessage<JsonNode>>() {}
		);

		// Then
		assertThat(eventMessage.eventId()).isEqualTo(eventId);
		assertThat(eventMessage.eventType()).isEqualTo("PAYMENT_APPROVED");
		assertThat(eventMessage.aggregateType()).isEqualTo("ORDER");
		assertThat(eventMessage.aggregateId()).isEqualTo(aggregateId);

		JsonNode payloadNode = eventMessage.payload();
		assertThat(payloadNode.path("paymentId").stringValue()).isEqualTo("payment-123");
		assertThat(payloadNode.path("amount").intValue()).isEqualTo(30000);
	}

	record OrderPaidPayload(UUID orderId, int amount) {
	}

	private enum TestEventType implements EventType {
		ORDER_PAID;

		@Override
		public String code() {
			return name();
		}
	}
}
