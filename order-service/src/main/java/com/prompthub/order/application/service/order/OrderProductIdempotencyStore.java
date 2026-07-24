package com.prompthub.order.application.service.order;

import java.time.Duration;
import java.util.Collection;
import java.util.UUID;

public interface OrderProductIdempotencyStore {
	boolean acquire(
		UUID buyerId,
		Collection<UUID> productIds,
		UUID reservationId,
		Duration ttl
	);

	boolean exists(UUID buyerId, UUID productId);

	void release(
		UUID buyerId,
		Collection<UUID> productIds,
		UUID reservationId
	);
}
