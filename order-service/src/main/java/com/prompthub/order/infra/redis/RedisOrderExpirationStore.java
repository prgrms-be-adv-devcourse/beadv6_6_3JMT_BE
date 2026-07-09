package com.prompthub.order.infra.redis;

import com.prompthub.order.application.service.order.OrderExpirationStore;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class RedisOrderExpirationStore implements OrderExpirationStore {

	private static final String EXPIRATION_KEY = "order:expiration";
	private static final String RETRY_KEY = "order:expiration:retry";
	private static final String DLQ_KEY = "order:expiration:dlq";

	private final StringRedisTemplate redisTemplate;

	@Override
	public void registerExpiration(UUID orderId, LocalDateTime createdAt, int expireAfterMinutes) {
		long expireAtMillis = createdAt.plusMinutes(expireAfterMinutes)
			.atZone(ZoneId.systemDefault())
			.toInstant()
			.toEpochMilli();

		redisTemplate.opsForZSet().add(EXPIRATION_KEY, orderId.toString(), expireAtMillis);
	}

	@Override
	public Set<UUID> findExpiredOrderIds(Instant now, int batchSize) {
		Set<String> orderIds = redisTemplate.opsForZSet()
			.rangeByScore(EXPIRATION_KEY, 0, now.toEpochMilli(), 0, batchSize);

		if (orderIds == null || orderIds.isEmpty()) {
			return Collections.emptySet();
		}

		return orderIds.stream()
			.map(UUID::fromString)
			.collect(Collectors.toSet());
	}

	@Override
	public void removeExpiration(UUID orderId) {
		redisTemplate.opsForZSet().remove(EXPIRATION_KEY, orderId.toString());
	}

	@Override
	public long incrementRetryCount(UUID orderId) {
		Long retryCount = redisTemplate.opsForHash()
			.increment(RETRY_KEY, orderId.toString(), 1);
		return retryCount == null ? 0L : retryCount;
	}

	@Override
	public void clearRetryCount(UUID orderId) {
		redisTemplate.opsForHash().delete(RETRY_KEY, orderId.toString());
	}

	@Override
	public void moveToDeadLetter(UUID orderId) {
		redisTemplate.opsForList().rightPush(DLQ_KEY, orderId.toString());
	}
}
