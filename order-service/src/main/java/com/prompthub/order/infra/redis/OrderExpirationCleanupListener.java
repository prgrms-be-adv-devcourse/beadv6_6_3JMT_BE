package com.prompthub.order.infra.redis;

import com.prompthub.order.application.event.order.OrderExpirationCleanupRequestedEvent;
import com.prompthub.order.application.service.order.OrderExpirationStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderExpirationCleanupListener {

	private final OrderExpirationStore orderExpirationStore;

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void cleanup(OrderExpirationCleanupRequestedEvent event) {
		try {
			orderExpirationStore.removeExpiration(event.orderId());
			orderExpirationStore.clearRetryCount(event.orderId());
		} catch (RuntimeException exception) {
			log.warn("주문 만료 Redis 정리에 실패했습니다. orderId={}", event.orderId(), exception);
		}
	}
}
