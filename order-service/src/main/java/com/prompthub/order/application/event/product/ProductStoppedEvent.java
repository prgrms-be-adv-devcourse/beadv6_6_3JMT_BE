package com.prompthub.order.application.event.product;

import java.time.LocalDateTime;
import java.util.UUID;

public record ProductStoppedEvent(
	String eventType,
	UUID productId,
	LocalDateTime occurredAt
) {
}
