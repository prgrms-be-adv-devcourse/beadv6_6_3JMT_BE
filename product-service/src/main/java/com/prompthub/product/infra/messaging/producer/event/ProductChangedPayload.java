package com.prompthub.product.infra.messaging.producer.event;

import java.util.UUID;

public record ProductChangedPayload(UUID familyRootId) {

	public static ProductChangedPayload of(UUID familyRootId) {
		return new ProductChangedPayload(familyRootId);
	}
}
