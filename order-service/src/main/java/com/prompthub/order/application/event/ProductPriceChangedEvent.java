package com.prompthub.order.application.event;

import java.time.LocalDateTime;
import java.util.UUID;

public record ProductPriceChangedEvent(
	UUID productId,
	int previousPrice,
	int changedPrice,
	LocalDateTime occurredAt
) {
}
