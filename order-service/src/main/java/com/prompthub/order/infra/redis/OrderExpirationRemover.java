package com.prompthub.order.infra.redis;

import com.prompthub.order.application.event.order.OrderPaidEvent;
import com.prompthub.order.application.service.order.OrderExpirationStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderExpirationRemover {

	private final OrderExpirationStore orderExpirationStore;

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void removeOrderExpiration(OrderPaidEvent event) {
		event.orderIds().forEach(this::removeQuietly);
	}

	private void removeQuietly(UUID orderId) {
		try {
			orderExpirationStore.removeExpiration(orderId);
			orderExpirationStore.clearRetryCount(orderId);
		} catch (Exception exception) {
			log.warn("결제 완료 주문의 Redis 만료 대상 제거에 실패했습니다. orderId={}", orderId, exception);
		}
	}
}
