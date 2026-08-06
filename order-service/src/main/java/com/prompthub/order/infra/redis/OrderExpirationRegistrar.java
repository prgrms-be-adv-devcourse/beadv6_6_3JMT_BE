package com.prompthub.order.infra.redis;

import com.prompthub.order.application.event.order.OrderCreatedEvent;
import com.prompthub.order.application.service.order.OrderExpirationStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderExpirationRegistrar {

	private final OrderExpirationStore orderExpirationStore;
	private final OrderExpirationProperties properties;

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void registerOrderExpiration(OrderCreatedEvent event) {
		try {
			orderExpirationStore.registerExpiration(
				event.orderId(),
				event.createdAt(),
				properties.paymentTimeoutMinutes()
			);
		} catch (RuntimeException exception) {
			log.warn(
				"주문 만료 Redis 등록에 실패했습니다. DB reconciliation으로 복구합니다. orderId={}",
				event.orderId(),
				exception
			);
		}
	}
}
