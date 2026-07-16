package com.prompthub.order.application.service.event.outbox;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.domain.enums.OutboxEventStatus;
import com.prompthub.order.domain.model.OutboxEvent;
import com.prompthub.order.domain.repository.OutboxEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class OutboxEventAppenderTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Mock
	private OutboxEventRepository outboxEventRepository;

	@Test
	@DisplayName("아웃박스 추가는 메시지만 받는다")
	void append_acceptsOnlyEventMessage() throws Exception {
		Method append = OutboxEventAppender.class.getMethod("append", EventMessage.class);

		assertThat(append.getParameterCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("다건 ORDER_CREATED EventMessage 전체를 JSON으로 직렬화해 Outbox에 저장한다")
	void append_savesSerializedOutboxEvent() throws Exception {
		OutboxEventAppender appender = new OutboxEventAppender(objectMapper, outboxEventRepository);

		UUID orderGroupId = UUID.randomUUID();
		LocalDateTime occurredAt = LocalDateTime.now();

		EventMessage<Map<String, Object>> message = new EventMessage<>(
			orderGroupId,
			"ORDER_CREATED",
			occurredAt,
			"ORDER_GROUP",
			orderGroupId,
			Map.of(
				"buyerId", UUID.randomUUID(),
				"totalAmount", 11_000,
				"orders", List.of(Map.of("orderId", UUID.randomUUID()))
			)
		);

		appender.append(message);

		ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
		then(outboxEventRepository).should().save(captor.capture());

		OutboxEvent saved = captor.getValue();
		assertThat(saved.getEventId()).isEqualTo(orderGroupId);
		assertThat(saved.getAggregateId()).isEqualTo(orderGroupId);
		assertThat(saved.getEventType()).isEqualTo("ORDER_CREATED");
		assertThat(saved.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
		assertThat(saved.getRetryCount()).isZero();
		assertThat(saved.getOccurredAt()).isEqualTo(occurredAt);
		assertThat(saved.getPublishedAt()).isNull();

		JsonNode json = objectMapper.readTree(saved.getPayload());
		assertThat(json.path("eventId").asText()).isEqualTo(orderGroupId.toString());
		assertThat(json.path("aggregateId").asText()).isEqualTo(orderGroupId.toString());
		assertThat(json.path("eventType").asText()).isEqualTo("ORDER_CREATED");
		assertThat(json.path("aggregateType").asText()).isEqualTo("ORDER_GROUP");
		assertThat(json.path("payload").path("totalAmount").intValue()).isEqualTo(11_000);
		assertThat(json.path("payload").path("orders")).hasSize(1);
	}
}
