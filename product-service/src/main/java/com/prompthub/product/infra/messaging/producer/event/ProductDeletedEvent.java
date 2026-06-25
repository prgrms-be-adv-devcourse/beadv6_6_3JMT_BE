package com.prompthub.product.infra.messaging.producer.event;

import java.time.LocalDateTime;
import java.util.UUID;

public record ProductDeletedEvent(
	String eventType,
	UUID productId,
	LocalDateTime occurredAt
) {
	public static ProductDeletedEvent of(UUID productId) {
		return new ProductDeletedEvent("PRODUCT_DELETED", productId, LocalDateTime.now());
	}
}
