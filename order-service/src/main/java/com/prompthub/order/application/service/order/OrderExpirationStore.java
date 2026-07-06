package com.prompthub.order.application.service.order;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

public interface OrderExpirationStore {

	void registerExpiration(UUID orderId, LocalDateTime createdAt, int expireAfterMinutes);

	Set<UUID> findExpiredOrderIds(Instant now, int batchSize);

	void removeExpiration(UUID orderId);

	long incrementRetryCount(UUID orderId);

	void clearRetryCount(UUID orderId);

	void moveToDeadLetter(UUID orderId);
}
