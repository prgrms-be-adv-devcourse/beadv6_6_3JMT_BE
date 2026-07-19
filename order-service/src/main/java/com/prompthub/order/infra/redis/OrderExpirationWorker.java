package com.prompthub.order.infra.redis;

import com.prompthub.order.application.service.order.OrderExpirationStore;
import com.prompthub.order.application.service.order.OrderFailureCompensationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
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
	private final OrderExpirationProperties properties;
	private final Clock clock;

	@Scheduled(fixedDelayString = "${prompthub.order.fixed-delay-ms:5000}")
	public void processExpiredOrders() {
		Instant now = Instant.now(clock);
		LocalDateTime nowLocal = LocalDateTime.ofInstant(now, clock.getZone());

		orderExpirationStore.findExpiredOrderIds(now, properties.batchSize())
			.forEach(orderId -> processExpiredOrder(orderId, nowLocal));
	}

	private void processExpiredOrder(UUID orderId, LocalDateTime now) {
		try {
			boolean completed = compensationService.compensateTimeout(orderId, now);
			if (completed) {
				orderExpirationStore.removeExpiration(orderId);
				orderExpirationStore.clearRetryCount(orderId);
			}
		} catch (Exception exception) {
			handleFailure(orderId, exception);
		}
	}

	private void handleFailure(UUID orderId, Exception exception) {
		long retryCount = orderExpirationStore.incrementRetryCount(orderId);
		if (retryCount > properties.maxRetryCount()) {
			orderExpirationStore.moveToDeadLetter(orderId);
			orderExpirationStore.removeExpiration(orderId);
			orderExpirationStore.clearRetryCount(orderId);
		}

		log.warn("주문 만료 처리에 실패했습니다. orderId={}, retryCount={}", orderId, retryCount, exception);
	}
}
