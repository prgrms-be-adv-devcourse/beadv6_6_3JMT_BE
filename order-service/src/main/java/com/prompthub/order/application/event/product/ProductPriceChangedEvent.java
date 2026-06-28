package com.prompthub.order.application.event.product;

import java.time.LocalDateTime;
import java.util.UUID;

public record ProductPriceChangedEvent(
	String eventType,
	UUID productId,
	int previousPrice,
	int changedPrice,
	LocalDateTime occurredAt
) {
}
