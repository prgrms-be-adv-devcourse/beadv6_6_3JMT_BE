package com.prompthub.order.application.event;

import java.time.LocalDateTime;
import java.util.UUID;

public record OrderEventEnvelope<T>(
	UUID eventId,
	String eventType,
	int version,
	LocalDateTime occurredAt,
	UUID aggregateId,
	T payload
) {
}
