package com.prompthub.order.application.service.event.outbox;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.domain.model.OutboxEvent;
import com.prompthub.order.domain.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OutboxEventAppender {

    private final OutboxPayloadSerializer outboxPayloadSerializer;
    private final OutboxEventRepository outboxEventRepository;

    public void append(EventMessage<?> message) {
        String payloadJson = outboxPayloadSerializer.serialize(message);

        OutboxEvent entity = OutboxEvent.create(
                message.eventId(),
                message.aggregateId(),
                message.eventType(),
                payloadJson,
                message.occurredAt()
        );

        outboxEventRepository.save(entity);
    }
}
