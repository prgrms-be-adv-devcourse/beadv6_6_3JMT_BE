package com.prompthub.order.infra.messaging.kafka.producer;

import com.prompthub.order.domain.enums.OutboxEventStatus;
import com.prompthub.order.domain.model.OutboxEvent;
import com.prompthub.order.domain.repository.OutboxEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static com.prompthub.order.fixture.OrderFixture.APPROVED_AT;
import static com.prompthub.order.fixture.OrderFixture.ORDER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private static final UUID NEXT_ORDER_ID =
		UUID.fromString("00000000-0000-0000-0000-000000000502");

	@Mock
	private OutboxEventRepository outboxEventRepository;

	@Mock
	private KafkaTemplate<String, Object> kafkaTemplate;

	@Test
	@DisplayName("PENDING Outbox 이벤트를 Kafka로 발행하고 성공 시 PUBLISHED 상태로 변경한다")
	void publishPendingEvents_publishesEventAndMarksPublished() throws Exception {
		OutboxEvent event = OutboxEvent.orderPaid(
			ORDER_ID,
			"""
				{"eventType":"ORDER_PAID","payload":{"orderId":"%s"}}
				""".formatted(ORDER_ID),
			APPROVED_AT
		);
		OutboxRelay relay = new OutboxRelay(
			outboxEventRepository,
			kafkaTemplate,
			objectMapper,
			new OutboxRelayProperties(true, 5_000L, 100, 3)
		);
		given(outboxEventRepository.findPendingEvents(100)).willReturn(List.of(event));
		given(kafkaTemplate.send(eq("order-events"), eq(ORDER_ID.toString()), any(Object.class)))
			.willReturn(CompletableFuture.completedFuture(null));

		relay.publishPendingEvents();

		ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
		then(kafkaTemplate).should()
			.send(eq("order-events"), eq(ORDER_ID.toString()), payloadCaptor.capture());
		assertThat(payloadCaptor.getValue()).isInstanceOf(JsonNode.class);
		assertThat(((JsonNode) payloadCaptor.getValue()).path("eventType").stringValue())
			.isEqualTo("ORDER_PAID");
		assertThat(((JsonNode) payloadCaptor.getValue()).path("payload").path("orderId").stringValue())
			.isEqualTo(ORDER_ID.toString());
		assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
		assertThat(event.getPublishedAt()).isNotNull();
	}

	@Test
	@DisplayName("Kafka 발행 실패 시 retry_count를 증가시키고 다음 이벤트 처리를 계속한다")
	void publishPendingEvents_recordsFailureAndContinues() {
		OutboxEvent failedEvent = OutboxEvent.orderPaid(
			ORDER_ID,
			"{\"eventType\":\"ORDER_PAID\",\"orderId\":\"%s\"}".formatted(ORDER_ID),
			APPROVED_AT
		);
		OutboxEvent nextEvent = OutboxEvent.orderPaid(
			NEXT_ORDER_ID,
			"{\"eventType\":\"ORDER_PAID\",\"orderId\":\"%s\"}".formatted(NEXT_ORDER_ID),
			APPROVED_AT.plusSeconds(1)
		);
		OutboxRelay relay = new OutboxRelay(
			outboxEventRepository,
			kafkaTemplate,
			objectMapper,
			new OutboxRelayProperties(true, 5_000L, 100, 3)
		);
		given(outboxEventRepository.findPendingEvents(100)).willReturn(List.of(failedEvent, nextEvent));
		given(kafkaTemplate.send(eq("order-events"), eq(ORDER_ID.toString()), any(Object.class)))
			.willReturn(CompletableFuture.failedFuture(new RuntimeException("Kafka unavailable")));
		given(kafkaTemplate.send(eq("order-events"), eq(NEXT_ORDER_ID.toString()), any(Object.class)))
			.willReturn(CompletableFuture.completedFuture(null));

		relay.publishPendingEvents();

		assertThat(failedEvent.getRetryCount()).isEqualTo(1);
		assertThat(failedEvent.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
		assertThat(nextEvent.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
	}

	@Test
	@DisplayName("Kafka 발행 실패 횟수가 최대 재시도 횟수에 도달하면 FAILED 상태로 변경한다")
	void publishPendingEvents_marksFailedWhenMaxRetryCountReached() {
		OutboxEvent event = OutboxEvent.orderPaid(
			ORDER_ID,
			"{\"eventType\":\"ORDER_PAID\",\"orderId\":\"%s\"}".formatted(ORDER_ID),
			APPROVED_AT
		);
		event.recordPublishFailure(3);
		event.recordPublishFailure(3);
		OutboxRelay relay = new OutboxRelay(
			outboxEventRepository,
			kafkaTemplate,
			objectMapper,
			new OutboxRelayProperties(true, 5_000L, 100, 3)
		);
		given(outboxEventRepository.findPendingEvents(100)).willReturn(List.of(event));
		given(kafkaTemplate.send(eq("order-events"), eq(ORDER_ID.toString()), any(Object.class)))
			.willReturn(CompletableFuture.failedFuture(new RuntimeException("Kafka unavailable")));

		relay.publishPendingEvents();

		assertThat(event.getRetryCount()).isEqualTo(3);
		assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
	}
}
