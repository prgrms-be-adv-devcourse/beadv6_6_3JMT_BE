package com.prompthub.order.infra.redis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;

import static com.prompthub.order.fixture.OrderFixture.ORDER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class RedisOrderExpirationStoreTest {

	private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
	private static final String EXPIRATION_KEY = "order:expiration";
	private static final String RETRY_KEY = "order:expiration:retry";
	private static final String DLQ_KEY = "order:expiration:dlq";
	private static final Instant NOW = Instant.parse("2026-06-20T12:30:00Z");

	@Mock
	private StringRedisTemplate redisTemplate;

	@Mock
	private ZSetOperations<String, String> zSetOperations;

	@Mock
	private HashOperations<String, Object, Object> hashOperations;

	@Mock
	private ListOperations<String, String> listOperations;

	@Test
	@DisplayName("주문 생성 시 만료 시각을 score로 Sorted Set에 등록한다")
	void registerExpiration_addsToZSet() {
		RedisOrderExpirationStore store = store();
		LocalDateTime createdAt = LocalDateTime.of(2026, 6, 20, 12, 0);
		double expectedScore = createdAt.plusMinutes(20)
			.atZone(SERVICE_ZONE)
			.toInstant()
			.toEpochMilli();
		given(redisTemplate.opsForZSet()).willReturn(zSetOperations);

		store.registerExpiration(ORDER_ID, createdAt, 20);

		then(zSetOperations).should().add(EXPIRATION_KEY, ORDER_ID.toString(), expectedScore);
	}

	@Test
	@DisplayName("현재 시각 이하 score의 주문 ID를 UUID 집합으로 조회한다")
	void findExpiredOrderIds_returnsUuidSet() {
		RedisOrderExpirationStore store = store();
		given(redisTemplate.opsForZSet()).willReturn(zSetOperations);
		given(zSetOperations.rangeByScore(EXPIRATION_KEY, 0, NOW.toEpochMilli(), 0, 100))
			.willReturn(Set.of(ORDER_ID.toString()));

		Set<UUID> result = store.findExpiredOrderIds(NOW, 100);

		assertThat(result).containsExactly(ORDER_ID);
	}

	@Test
	@DisplayName("주문 만료 대상을 Sorted Set에서 제거한다")
	void removeExpiration_removesFromZSet() {
		RedisOrderExpirationStore store = store();
		given(redisTemplate.opsForZSet()).willReturn(zSetOperations);

		store.removeExpiration(ORDER_ID);

		then(zSetOperations).should().remove(EXPIRATION_KEY, ORDER_ID.toString());
	}

	@Test
	@DisplayName("주문별 재시도 횟수를 Hash에서 증가시킨다")
	void incrementRetryCount_incrementsHash() {
		RedisOrderExpirationStore store = store();
		given(redisTemplate.opsForHash()).willReturn(hashOperations);
		given(hashOperations.increment(RETRY_KEY, ORDER_ID.toString(), 1))
			.willReturn(2L);

		long retryCount = store.incrementRetryCount(ORDER_ID);

		assertThat(retryCount).isEqualTo(2L);
	}

	@Test
	@DisplayName("DLQ List에 주문 ID를 추가한다")
	void moveToDeadLetter_pushesToList() {
		RedisOrderExpirationStore store = store();
		given(redisTemplate.opsForList()).willReturn(listOperations);

		store.moveToDeadLetter(ORDER_ID);

		then(listOperations).should().rightPush(DLQ_KEY, ORDER_ID.toString());
	}

	private RedisOrderExpirationStore store() {
		return new RedisOrderExpirationStore(redisTemplate);
	}
}
