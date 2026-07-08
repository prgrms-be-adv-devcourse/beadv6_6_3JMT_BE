package com.prompthub.settlement.application.event;

import java.time.LocalDateTime;
import java.util.UUID;

public record SettlementEventEnvelope<T>(
        UUID eventId,
        String eventType,
        int version,
        LocalDateTime occurredAt,
        UUID aggregateId,
        T payload
) {
}
