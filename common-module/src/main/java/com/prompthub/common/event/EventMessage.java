package com.prompthub.common.event;

import java.time.LocalDateTime;
import java.util.UUID;

public record EventMessage<T>(
        UUID eventId,
        String eventType,
        LocalDateTime occurredAt,
        String aggregateType,
        UUID aggregateId,
        T payload
) {

    public static <T> EventMessage<T> create(
            EventType eventType,
            LocalDateTime occurredAt,
            String aggregateType,
            UUID aggregateId,
            T payload
    ) {
        return new EventMessage<>(
                UUID.randomUUID(),
                eventType.code(),
                occurredAt,
                aggregateType,
                aggregateId,
                payload
        );
    }
}
