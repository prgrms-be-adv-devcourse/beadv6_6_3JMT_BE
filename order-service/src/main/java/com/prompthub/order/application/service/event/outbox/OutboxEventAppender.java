package com.prompthub.order.application.service.event.outbox;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.domain.model.OutboxEvent;
import com.prompthub.order.domain.repository.OutboxEventRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class OutboxEventAppender {

    private final ObjectMapper objectMapper;
    private final OutboxEventRepository outboxEventRepository;

    public void append(EventMessage<?> message) {
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
            throw new OrderException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }
}
