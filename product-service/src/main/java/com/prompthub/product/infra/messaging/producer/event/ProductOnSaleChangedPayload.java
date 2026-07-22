package com.prompthub.product.infra.messaging.producer.event;

import java.util.UUID;

public record ProductOnSaleChangedPayload(UUID familyRootId) {

	public static ProductOnSaleChangedPayload of(UUID familyRootId) {
		return new ProductOnSaleChangedPayload(familyRootId);
	}
}
