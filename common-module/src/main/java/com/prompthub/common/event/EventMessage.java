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
}
