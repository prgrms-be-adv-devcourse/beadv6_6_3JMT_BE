package com.prompthub.order.infra.redis;

import com.prompthub.order.application.service.order.OrderExpirationStore;
import com.prompthub.order.application.service.order.OrderFailureCompensationService;
import com.prompthub.order.domain.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
	prefix = "prompthub.order",
	name = "enabled",
	havingValue = "true",
	matchIfMissing = true
)
public class OrderExpirationWorker {

	private final OrderExpirationStore orderExpirationStore;
	private final OrderFailureCompensationService compensationService;
	private final OrderRepository orderRepository;
	private final OrderExpirationProperties properties;
	private final Clock clock;

	@Scheduled(fixedDelayString = "${prompthub.order.fixed-delay-ms:5000}")
	public void processExpiredOrders() {
		Instant now = Instant.now(clock);
		LocalDateTime nowLocal = LocalDateTime.ofInstant(now, clock.getZone());

		Set<UUID> candidates = new LinkedHashSet<>();
		loadDatabaseCandidates(nowLocal.minusMinutes(properties.paymentTimeoutMinutes()), candidates);
		loadRedisCandidates(now, candidates);
		candidates.stream()
			.limit(properties.batchSize())
			.forEach(orderId -> processExpiredOrder(orderId, nowLocal));
	}

	private void loadDatabaseCandidates(LocalDateTime cutoff, Set<UUID> candidates) {
		try {
			candidates.addAll(orderRepository.findExpiredCreatedOrderIds(cutoff, properties.batchSize()));
		} catch (RuntimeException exception) {
			log.warn("DB 기준 주문 만료 후보 조회에 실패했습니다. cutoff={}", cutoff, exception);
		}
	}

	private void loadRedisCandidates(Instant now, Set<UUID> candidates) {
		try {
			candidates.addAll(orderExpirationStore.findExpiredOrderIds(now, properties.batchSize()));
		} catch (RuntimeException exception) {
			log.warn("Redis 기준 주문 만료 후보 조회에 실패했습니다. DB reconciliation 결과를 계속 처리합니다.", exception);
		}
	}

	private void processExpiredOrder(UUID orderId, LocalDateTime now) {
		try {
			boolean completed = compensationService.compensateTimeout(orderId, now);
			if (completed) {
				cleanupRedisState(orderId);
			}
		} catch (Exception exception) {
			handleFailure(orderId, exception);
		}
	}

	private void handleFailure(UUID orderId, Exception exception) {
		long retryCount;
		try {
			retryCount = orderExpirationStore.incrementRetryCount(orderId);
		} catch (RuntimeException retryException) {
			log.warn("주문 만료 재시도 상태 저장에 실패했습니다. orderId={}", orderId, retryException);
			return;
		}
		if (retryCount == properties.maxRetryCount() + 1L) {
			try {
				orderExpirationStore.moveToDeadLetter(orderId);
			} catch (RuntimeException dlqException) {
				log.warn("주문 만료 DLQ 기록에 실패했습니다. orderId={}", orderId, dlqException);
			}
		}
		if (retryCount > properties.maxRetryCount()) {
			try {
				orderExpirationStore.removeExpiration(orderId);
			} catch (RuntimeException removeException) {
				log.warn("주문 만료 Redis 대상 제거에 실패했습니다. orderId={}", orderId, removeException);
			}
		}

		log.warn("주문 만료 처리에 실패했습니다. orderId={}, retryCount={}", orderId, retryCount, exception);
	}

	private void cleanupRedisState(UUID orderId) {
		try {
			orderExpirationStore.removeExpiration(orderId);
		} catch (RuntimeException exception) {
			log.warn("주문 만료 Redis 대상 제거에 실패했습니다. orderId={}", orderId, exception);
		}
		try {
			orderExpirationStore.clearRetryCount(orderId);
		} catch (RuntimeException exception) {
			log.warn("주문 만료 재시도 카운트 제거에 실패했습니다. orderId={}", orderId, exception);
		}
	}
}
