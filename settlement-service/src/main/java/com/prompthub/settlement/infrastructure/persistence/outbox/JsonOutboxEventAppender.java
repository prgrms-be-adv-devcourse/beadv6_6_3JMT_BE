package com.prompthub.settlement.infrastructure.persistence.outbox;

import com.prompthub.common.event.EventMessage;
import com.prompthub.settlement.application.event.SettlementCreatedEvent;
import com.prompthub.settlement.application.event.SettlementEventType;
import com.prompthub.settlement.application.port.OutboxEventAppender;
import com.prompthub.settlement.domain.model.SettlementOutboxEvent;
import com.prompthub.settlement.domain.repository.OutboxEventRepository;
import com.prompthub.settlement.global.exception.SettlementErrorCode;
import com.prompthub.settlement.global.exception.SettlementException;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class JsonOutboxEventAppender implements OutboxEventAppender {

    private static final String AGGREGATE_TYPE = "SETTLEMENT";

    private final ObjectMapper objectMapper;
    private final OutboxEventRepository repository;
    private final String topic;

    public JsonOutboxEventAppender(
            ObjectMapper objectMapper,
            OutboxEventRepository repository,
            @Value("${settlement.kafka.producer.topic}") String topic) {
        this.objectMapper = objectMapper;
        this.repository = repository;
        this.topic = topic;
    }

    @Override
    public void appendSettlementCreated(UUID settlementBatchId, SettlementCreatedEvent event) {
        UUID eventId = UUID.randomUUID();
        LocalDateTime occurredAt = LocalDateTime.now();
        String eventType = SettlementEventType.SETTLEMENT_CREATED.code();
        EventMessage<SettlementCreatedEvent> message = new EventMessage<>(
                eventId,
                eventType,
                occurredAt,
                AGGREGATE_TYPE,
                event.settlementId(),
                event);

        repository.save(SettlementOutboxEvent.create(
                eventId,
                settlementBatchId,
                AGGREGATE_TYPE,
                event.settlementId(),
                eventType,
                topic,
                serialize(message),
                occurredAt));
    }

    private String serialize(EventMessage<?> message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JacksonException exception) {
            throw new SettlementException(
                    SettlementErrorCode.OUTBOX_EVENT_SERIALIZE_FAILED,
                    exception);
        }
    }
}
