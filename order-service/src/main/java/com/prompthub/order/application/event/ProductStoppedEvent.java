package com.prompthub.order.application.event;

import java.time.LocalDateTime;
import java.util.UUID;

public record ProductStoppedEvent(
	UUID productId,
	LocalDateTime occurredAt
) {
}
