package com.prompthub.order.infra.redis;

import com.prompthub.order.application.service.order.OrderProductReservationMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static com.prompthub.order.application.service.order.OrderProductReservationMetrics.RedisOperation.ACQUIRE;
import static com.prompthub.order.application.service.order.OrderProductReservationMetrics.RedisOperation.EXISTS;
import static com.prompthub.order.application.service.order.OrderProductReservationMetrics.RedisOperation.RELEASE;
import static com.prompthub.order.application.service.order.OrderProductReservationMetrics.RedisOutcome.ERROR;
import static com.prompthub.order.application.service.order.OrderProductReservationMetrics.RedisOutcome.SUCCESS;
import static com.prompthub.order.fixture.PaymentEventFixture.BUYER_ID;
import static com.prompthub.order.fixture.PaymentEventFixture.ORDER_A;
import static com.prompthub.order.fixture.PaymentEventFixture.PRODUCT_A;
import static com.prompthub.order.fixture.PaymentEventFixture.PRODUCT_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class RedisOrderProductIdempotencyStoreTest {

	private static final Duration TTL = Duration.ofMinutes(30);

	@Mock
	private StringRedisTemplate redisTemplate;

	@Mock
	private ValueOperations<String, String> valueOperations;

	@Mock
	private OrderProductReservationMetrics metrics;

	private RedisOrderProductIdempotencyStore store;

	@BeforeEach
	void setUp() {
		lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		store = new RedisOrderProductIdempotencyStore(redisTemplate, metrics);
	}

	@Test
	@DisplayName("모든 상품의 예약 키를 SET NX로 획득한다")
	void acquire_allKeysAvailable_returnsTrue() {
		given(valueOperations.setIfAbsent(any(String.class), any(String.class), any(Duration.class)))
			.willReturn(true);

		assertThat(store.acquire(BUYER_ID, List.of(PRODUCT_B, PRODUCT_A), ORDER_A, TTL)).isTrue();

		then(valueOperations).should(times(2))
			.setIfAbsent(any(String.class), org.mockito.ArgumentMatchers.eq(ORDER_A.toString()), org.mockito.ArgumentMatchers.eq(TTL));
		then(metrics).should().recordRedis(eq(ACQUIRE), eq(SUCCESS), any(Duration.class));
	}

	@Test
	@DisplayName("일부 키가 충돌하면 이미 획득한 키를 토큰으로 해제하고 false를 반환한다")
	void acquire_partialConflict_releasesAcquiredKeys() {
		given(valueOperations.setIfAbsent(any(String.class), any(String.class), any(Duration.class)))
			.willReturn(true, false);
		given(redisTemplate.execute(any(RedisScript.class), anyList(), any())).willReturn(1L);

		assertThat(store.acquire(BUYER_ID, List.of(PRODUCT_A, PRODUCT_B), ORDER_A, TTL)).isFalse();

		then(redisTemplate).should().execute(
			any(RedisScript.class),
			org.mockito.ArgumentMatchers.eq(List.of("order:product:idempotency:{" + BUYER_ID + "}:" + PRODUCT_A)),
			org.mockito.ArgumentMatchers.eq(ORDER_A.toString())
		);
		then(metrics).should().recordRedis(eq(ACQUIRE), eq(SUCCESS), any(Duration.class));
	}

	@Test
	@DisplayName("예약 존재 여부를 Redis key 존재 여부로 확인한다")
	void exists_delegatesToHasKey() {
		given(redisTemplate.hasKey("order:product:idempotency:{" + BUYER_ID + "}:" + PRODUCT_A))
			.willReturn(true);

		assertThat(store.exists(BUYER_ID, PRODUCT_A)).isTrue();

		then(metrics).should().recordRedis(eq(EXISTS), eq(SUCCESS), any(Duration.class));
	}

	@Test
	@DisplayName("상품 예약 해제는 모든 키에 compare-and-delete를 실행한다")
	void release_executesCompareAndDeleteForAllProducts() {
		given(redisTemplate.execute(any(RedisScript.class), anyList(), any())).willReturn(1L);

		store.release(BUYER_ID, List.of(PRODUCT_B, PRODUCT_A), ORDER_A);

		then(redisTemplate).should(times(2))
			.execute(any(RedisScript.class), anyList(), org.mockito.ArgumentMatchers.eq(ORDER_A.toString()));
		then(metrics).should().recordRedis(eq(RELEASE), eq(SUCCESS), any(Duration.class));
	}

	@Test
	@DisplayName("Redis 연산 실패 시 오류 latency를 기록하고 예외를 전달한다")
	void exists_redisFailure_recordsErrorAndRethrows() {
		willThrow(new IllegalStateException("redis down"))
			.given(redisTemplate).hasKey("order:product:idempotency:{" + BUYER_ID + "}:" + PRODUCT_A);

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.exists(BUYER_ID, PRODUCT_A))
			.isInstanceOf(IllegalStateException.class);

		then(metrics).should().recordRedis(eq(EXISTS), eq(ERROR), any(Duration.class));
	}
}
