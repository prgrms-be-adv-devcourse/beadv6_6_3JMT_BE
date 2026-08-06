package com.prompthub.order.application.dto.outbox;

import com.prompthub.order.domain.enums.OutboxEventStatus;
import com.prompthub.order.domain.model.OutboxEvent;

import java.time.LocalDateTime;
import java.util.UUID;

public record OutboxRedriveResult(
    UUID eventId,
    OutboxEventStatus status,
    LocalDateTime nextAttemptAt
) {

    public static OutboxRedriveResult from(OutboxEvent event) {
        return new OutboxRedriveResult(
            event.getEventId(),
            event.getStatus(),
            event.getNextAttemptAt()
        );
    }
}
