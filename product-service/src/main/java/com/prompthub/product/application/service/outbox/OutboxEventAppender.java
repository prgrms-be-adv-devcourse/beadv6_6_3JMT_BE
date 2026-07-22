package com.prompthub.product.application.service.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prompthub.common.event.EventMessage;
import com.prompthub.product.domain.model.entity.OutboxEvent;
import com.prompthub.product.domain.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OutboxEventAppender {

	private final ObjectMapper objectMapper;
	private final OutboxEventRepository outboxEventRepository;

	public void append(EventMessage<?> message) {
		String payloadJson = serialize(message);
		OutboxEvent entity = OutboxEvent.create(
			message.eventId(), message.aggregateId(), message.eventType(), payloadJson, message.occurredAt()
		);
		outboxEventRepository.save(entity);
	}

	private String serialize(EventMessage<?> message) {
		try {
			return objectMapper.writeValueAsString(message);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("아웃박스 이벤트 직렬화에 실패했습니다. eventId=" + message.eventId(), e);
		}
	}
}
