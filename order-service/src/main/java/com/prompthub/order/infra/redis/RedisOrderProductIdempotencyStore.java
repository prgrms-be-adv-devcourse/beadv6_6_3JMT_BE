package com.prompthub.order.infra.redis;

import com.prompthub.order.application.service.order.OrderProductIdempotencyStore;
import com.prompthub.order.application.service.order.OrderProductReservationMetrics;
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
import java.util.function.Supplier;

import static com.prompthub.order.application.service.order.OrderProductReservationMetrics.RedisOperation.ACQUIRE;
import static com.prompthub.order.application.service.order.OrderProductReservationMetrics.RedisOperation.EXISTS;
import static com.prompthub.order.application.service.order.OrderProductReservationMetrics.RedisOperation.RELEASE;
import static com.prompthub.order.application.service.order.OrderProductReservationMetrics.RedisOutcome.ERROR;
import static com.prompthub.order.application.service.order.OrderProductReservationMetrics.RedisOutcome.SUCCESS;

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
	private final OrderProductReservationMetrics metrics;

	@Override
	public boolean acquire(
		UUID buyerId,
		Collection<UUID> productIds,
		UUID reservationId,
		Duration ttl
	) {
		return observe(ACQUIRE, () ->
			acquireKeys(buyerId, productIds, reservationId, ttl)
		);
	}

	private boolean acquireKeys(
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
		return observe(
			EXISTS,
			() -> Boolean.TRUE.equals(redisTemplate.hasKey(key(buyerId, productId)))
		);
	}

	@Override
	public void release(UUID buyerId, Collection<UUID> productIds, UUID reservationId) {
		List<String> keys = productIds.stream()
			.distinct()
			.sorted()
			.map(productId -> key(buyerId, productId))
			.toList();
		observe(RELEASE, () -> {
			releaseKeys(keys, reservationId);
			return null;
		});
	}

	private <T> T observe(
		OrderProductReservationMetrics.RedisOperation operation,
		Supplier<T> action
	) {
		long startedAt = System.nanoTime();
		try {
			T result = action.get();
			metrics.recordRedis(
				operation,
				SUCCESS,
				Duration.ofNanos(System.nanoTime() - startedAt)
			);
			return result;
		} catch (RuntimeException exception) {
			metrics.recordRedis(
				operation,
				ERROR,
				Duration.ofNanos(System.nanoTime() - startedAt)
			);
			throw exception;
		}
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
