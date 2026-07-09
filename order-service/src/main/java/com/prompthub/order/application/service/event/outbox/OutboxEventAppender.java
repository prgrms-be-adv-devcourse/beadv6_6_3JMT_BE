package com.prompthub.order.application.service.event.outbox;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.domain.model.OutboxEvent;
import com.prompthub.order.domain.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class OutboxEventAppender {

    private final ObjectMapper objectMapper;
    private final OutboxEventRepository outboxEventRepository;

    public void append(String topic, EventMessage<?> message) {
        String payloadJson = serialize(message);

        OutboxEvent entity = OutboxEvent.create(
                message.eventId(),
                message.aggregateId(),
                message.eventType(),
                payloadJson,
                message.occurredAt()
        );

        outboxEventRepository.save(entity);
    }

    private String serialize(EventMessage<?> message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JacksonException e) {
            throw new RuntimeException("Outbox event serialize failed", e);
        }
    }
}
