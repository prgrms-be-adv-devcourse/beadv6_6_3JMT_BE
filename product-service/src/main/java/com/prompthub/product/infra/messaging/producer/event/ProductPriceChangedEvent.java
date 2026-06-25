package com.prompthub.product.infra.messaging.producer.event;

import java.time.LocalDateTime;
import java.util.UUID;

public record ProductPriceChangedEvent(
	String eventType,
	UUID productId,
	int previousPrice,
	int changedPrice,
	LocalDateTime occurredAt
) {
	public static ProductPriceChangedEvent of(UUID productId, int previousPrice, int changedPrice) {
		return new ProductPriceChangedEvent("PRODUCT_PRICE_CHANGED", productId, previousPrice, changedPrice, LocalDateTime.now());
	}
}
