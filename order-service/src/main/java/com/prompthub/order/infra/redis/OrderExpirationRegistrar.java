package com.prompthub.order.infra.redis;

import com.prompthub.order.application.event.order.OrderCreatedEvent;
import com.prompthub.order.application.service.order.OrderExpirationStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class OrderExpirationRegistrar {

	private final OrderExpirationStore orderExpirationStore;
	private final OrderExpirationProperties properties;

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void registerOrderExpiration(OrderCreatedEvent event) {
		event.orders().forEach(order -> orderExpirationStore.registerExpiration(
			order.orderId(),
			order.createdAt(),
			properties.paymentTimeoutMinutes()
		));
	}
}
