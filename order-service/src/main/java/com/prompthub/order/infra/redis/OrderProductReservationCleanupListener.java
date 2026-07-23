package com.prompthub.order.infra.redis;

import com.prompthub.order.application.event.order.OrderProductReservationCleanupRequestedEvent;
import com.prompthub.order.application.service.order.OrderProductIdempotencyStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderProductReservationCleanupListener {

	private final OrderProductIdempotencyStore store;

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void cleanup(OrderProductReservationCleanupRequestedEvent event) {
		try {
			store.release(event.buyerId(), event.productIds(), event.orderId());
		} catch (RuntimeException exception) {
			log.warn(
				"주문 상품 Redis 예약 정리에 실패했습니다. orderId={}, buyerId={}",
				event.orderId(),
				event.buyerId(),
				exception
			);
		}
	}
}
