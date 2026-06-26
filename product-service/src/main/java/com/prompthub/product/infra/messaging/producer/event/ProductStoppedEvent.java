package com.prompthub.product.infra.messaging.producer.event;

import java.time.LocalDateTime;
import java.util.UUID;

public record ProductStoppedEvent(
	String eventType,
	UUID productId,
	LocalDateTime occurredAt
) {
	public static ProductStoppedEvent of(UUID productId) {
		return new ProductStoppedEvent("PRODUCT_STOPPED", productId, LocalDateTime.now());
	}
}
