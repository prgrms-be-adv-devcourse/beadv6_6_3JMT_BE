package com.prompthub.order.infra.redis;

import com.prompthub.order.application.service.order.OrderExpirationStore;
import com.prompthub.order.application.service.order.OrderFailureCompensationService;
import com.prompthub.order.domain.repository.OrderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.prompthub.order.fixture.OrderFixture.ORDER_ID;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class OrderExpirationWorkerTest {

	private static final Instant NOW = Instant.parse("2026-06-20T12:30:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("UTC"));
	private static final LocalDateTime NOW_LOCAL = LocalDateTime.ofInstant(NOW, ZoneId.of("UTC"));

	@Mock
	private OrderExpirationStore orderExpirationStore;

	@Mock
	private OrderFailureCompensationService compensationService;

	@Mock
	private OrderRepository orderRepository;

	@Test
	@DisplayName("만료 대상 주문 처리 완료 시 Redis 대상과 재시도 카운트를 제거한다")
	void processExpiredOrders_completed_removesExpirationAndRetry() {
		OrderExpirationWorker worker = worker();
		given(orderExpirationStore.findExpiredOrderIds(NOW, 100))
			.willReturn(Set.of(ORDER_ID));
		given(compensationService.compensateTimeout(ORDER_ID, NOW_LOCAL))
			.willReturn(true);

		worker.processExpiredOrders();

		then(orderExpirationStore).should().removeExpiration(ORDER_ID);
		then(orderExpirationStore).should().clearRetryCount(ORDER_ID);
	}

	@Test
	@DisplayName("DB 기준 아직 만료되지 않은 주문이면 Redis 대상에서 제거하지 않는다")
	void processExpiredOrders_notCompleted_keepsExpiration() {
		OrderExpirationWorker worker = worker();
		given(orderExpirationStore.findExpiredOrderIds(NOW, 100))
			.willReturn(Set.of(ORDER_ID));
		given(compensationService.compensateTimeout(ORDER_ID, NOW_LOCAL))
			.willReturn(false);

		worker.processExpiredOrders();

		then(orderExpirationStore).should(never()).removeExpiration(ORDER_ID);
		then(orderExpirationStore).should(never()).clearRetryCount(ORDER_ID);
	}

	@Test
	@DisplayName("처리 실패 횟수가 최대 재시도 이하이면 다음 worker 실행 때 재시도하도록 남겨둔다")
	void processExpiredOrders_failureUnderMaxRetry_keepsExpiration() {
		OrderExpirationWorker worker = worker();
		given(orderExpirationStore.findExpiredOrderIds(NOW, 100))
			.willReturn(Set.of(ORDER_ID));
		willThrow(new RuntimeException("DB unavailable"))
			.given(compensationService).compensateTimeout(ORDER_ID, NOW_LOCAL);
		given(orderExpirationStore.incrementRetryCount(ORDER_ID))
			.willReturn(3L);

		worker.processExpiredOrders();

		then(compensationService).should(times(1)).compensateTimeout(ORDER_ID, NOW_LOCAL);
		then(orderExpirationStore).should(times(1)).incrementRetryCount(ORDER_ID);
		then(orderExpirationStore).should(never()).moveToDeadLetter(ORDER_ID);
		then(orderExpirationStore).should(never()).removeExpiration(ORDER_ID);
		then(orderExpirationStore).should(never()).clearRetryCount(ORDER_ID);
	}

	@Test
	@DisplayName("처리 실패 횟수가 최대 재시도를 초과하면 DLQ로 옮기고 Redis 대상에서 제거한다")
	void processExpiredOrders_failureOverMaxRetry_movesToDeadLetter() {
		OrderExpirationWorker worker = worker();
		given(orderExpirationStore.findExpiredOrderIds(NOW, 100))
			.willReturn(Set.of(ORDER_ID));
		willThrow(new RuntimeException("DB unavailable"))
			.given(compensationService).compensateTimeout(ORDER_ID, NOW_LOCAL);
		given(orderExpirationStore.incrementRetryCount(ORDER_ID))
			.willReturn(4L);

		worker.processExpiredOrders();

		then(orderExpirationStore).should().moveToDeadLetter(ORDER_ID);
		then(orderExpirationStore).should().removeExpiration(ORDER_ID);
		then(orderExpirationStore).should(never()).clearRetryCount(ORDER_ID);
	}

	@Test
	@DisplayName("Redis에 등록되지 않은 DB 만료 주문도 보상한다")
	void processExpiredOrders_databaseCandidate_isCompensated() {
		OrderExpirationWorker worker = worker();
		given(orderRepository.findExpiredCreatedOrderIds(NOW_LOCAL.minusMinutes(20), 100))
			.willReturn(List.of(ORDER_ID));
		given(orderExpirationStore.findExpiredOrderIds(NOW, 100)).willReturn(Set.of());
		given(compensationService.compensateTimeout(ORDER_ID, NOW_LOCAL)).willReturn(true);

		worker.processExpiredOrders();

		then(compensationService).should().compensateTimeout(ORDER_ID, NOW_LOCAL);
	}

	@Test
	@DisplayName("Redis 후보 조회가 실패해도 DB 만료 후보를 처리한다")
	void processExpiredOrders_redisLookupFailure_processesDatabaseCandidate() {
		OrderExpirationWorker worker = worker();
		given(orderRepository.findExpiredCreatedOrderIds(NOW_LOCAL.minusMinutes(20), 100))
			.willReturn(List.of(ORDER_ID));
		willThrow(new IllegalStateException("redis down"))
			.given(orderExpirationStore).findExpiredOrderIds(NOW, 100);
		given(compensationService.compensateTimeout(ORDER_ID, NOW_LOCAL)).willReturn(true);

		worker.processExpiredOrders();

		then(compensationService).should().compensateTimeout(ORDER_ID, NOW_LOCAL);
	}

	@Test
	@DisplayName("DB와 Redis에 같은 후보가 있으면 한 번만 보상한다")
	void processExpiredOrders_duplicateCandidates_areProcessedOnce() {
		OrderExpirationWorker worker = worker();
		given(orderRepository.findExpiredCreatedOrderIds(NOW_LOCAL.minusMinutes(20), 100))
			.willReturn(List.of(ORDER_ID));
		given(orderExpirationStore.findExpiredOrderIds(NOW, 100)).willReturn(Set.of(ORDER_ID));
		given(compensationService.compensateTimeout(ORDER_ID, NOW_LOCAL)).willReturn(true);

		worker.processExpiredOrders();

		then(compensationService).should(times(1)).compensateTimeout(ORDER_ID, NOW_LOCAL);
	}

	private OrderExpirationWorker worker() {
		return new OrderExpirationWorker(
			orderExpirationStore,
			compensationService,
			orderRepository,
			new OrderExpirationProperties(true, 20, 5_000L, 100, 3),
			CLOCK
		);
	}
}
