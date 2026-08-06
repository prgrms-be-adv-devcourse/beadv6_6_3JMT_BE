package com.prompthub.order.application.dto.outbox;

import com.prompthub.order.domain.enums.OutboxEventStatus;
import com.prompthub.order.domain.model.OutboxEvent;

import java.time.LocalDateTime;
import java.util.UUID;

public record OutboxEventSummary(
    UUID eventId,
    UUID aggregateId,
    String eventType,
    OutboxEventStatus status,
    int retryCount,
    LocalDateTime occurredAt,
    LocalDateTime lastAttemptAt,
    String lastError
) {

    public static OutboxEventSummary from(OutboxEvent event) {
        return new OutboxEventSummary(
            event.getEventId(),
            event.getAggregateId(),
            event.getEventType(),
            event.getStatus(),
            event.getRetryCount(),
            event.getOccurredAt(),
            event.getLastAttemptAt(),
            event.getLastError()
        );
    }
}
