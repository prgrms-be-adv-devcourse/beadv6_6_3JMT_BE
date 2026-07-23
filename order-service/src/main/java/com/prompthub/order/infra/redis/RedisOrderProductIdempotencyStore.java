package com.prompthub.order.infra.redis;

import com.prompthub.order.application.service.order.OrderProductIdempotencyStore;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class RedisOrderProductIdempotencyStore implements OrderProductIdempotencyStore {

	private static final String KEY_PREFIX = "order:product:idempotency:";
	private static final RedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
		"if redis.call('get', KEYS[1]) == ARGV[1] then "
			+ "return redis.call('del', KEYS[1]) else return 0 end",
		Long.class
	);

	private final StringRedisTemplate redisTemplate;

	@Override
	public boolean acquire(
		UUID buyerId,
		Collection<UUID> productIds,
		UUID reservationId,
		Duration ttl
	) {
		List<String> acquiredKeys = new ArrayList<>();
		try {
			for (UUID productId : productIds.stream().distinct().sorted().toList()) {
				String key = key(buyerId, productId);
				Boolean acquired = redisTemplate.opsForValue()
					.setIfAbsent(key, reservationId.toString(), ttl);
				if (!Boolean.TRUE.equals(acquired)) {
					releaseKeys(acquiredKeys, reservationId);
					return false;
				}
				acquiredKeys.add(key);
			}
			return true;
		} catch (RuntimeException exception) {
			try {
				releaseKeys(acquiredKeys, reservationId);
			} catch (RuntimeException cleanupException) {
				exception.addSuppressed(cleanupException);
			}
			throw exception;
		}
	}

	@Override
	public boolean exists(UUID buyerId, UUID productId) {
		return Boolean.TRUE.equals(redisTemplate.hasKey(key(buyerId, productId)));
	}

	@Override
	public void release(UUID buyerId, Collection<UUID> productIds, UUID reservationId) {
		List<String> keys = productIds.stream()
			.distinct()
			.sorted()
			.map(productId -> key(buyerId, productId))
			.toList();
		releaseKeys(keys, reservationId);
	}

	private void releaseKeys(Collection<String> keys, UUID reservationId) {
		RuntimeException firstFailure = null;
		for (String key : keys) {
			try {
				redisTemplate.execute(RELEASE_SCRIPT, List.of(key), reservationId.toString());
			} catch (RuntimeException exception) {
				if (firstFailure == null) {
					firstFailure = exception;
				} else {
					firstFailure.addSuppressed(exception);
				}
			}
		}
		if (firstFailure != null) {
			throw firstFailure;
		}
	}

	private String key(UUID buyerId, UUID productId) {
		return KEY_PREFIX + "{" + buyerId + "}:" + productId;
	}
}
