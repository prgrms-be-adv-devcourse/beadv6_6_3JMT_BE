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
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class OutboxEventAppenderTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Mock
	private OutboxEventRepository outboxEventRepository;

	@Test
	@DisplayName("EventMessage를 직렬화하여 OutboxEvent로 저장한다")
	void append_savesSerializedOutboxEvent() throws Exception {
		OutboxEventAppender appender = new OutboxEventAppender(objectMapper, outboxEventRepository);
		
		UUID eventId = UUID.randomUUID();
		UUID aggregateId = UUID.randomUUID();
		LocalDateTime occurredAt = LocalDateTime.now();
		
		EventMessage<DummyPayload> message = new EventMessage<>(
			eventId,
			"ORDER_PAID",
			occurredAt,
			"ORDER",
			aggregateId,
			new DummyPayload("test")
		);

		appender.append("order-events", message);

		ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
		then(outboxEventRepository).should().save(captor.capture());

		OutboxEvent saved = captor.getValue();
		assertThat(saved.getOrderId()).isEqualTo(aggregateId);
		assertThat(saved.getEventType()).isEqualTo("ORDER_PAID");
		assertThat(saved.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
		assertThat(saved.getRetryCount()).isZero();
		assertThat(saved.getOccurredAt()).isEqualTo(occurredAt);
		assertThat(saved.getPublishedAt()).isNull();

		// 검증 로직 추가 가능
		assertThat(saved.getPayload()).contains("test");
	}

	private record DummyPayload(String data) {}
}
