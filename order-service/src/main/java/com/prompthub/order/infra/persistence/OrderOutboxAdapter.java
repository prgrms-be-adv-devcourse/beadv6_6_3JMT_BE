package com.prompthub.order.infra.persistence;

import com.prompthub.order.domain.model.OrderOutbox;
import com.prompthub.order.domain.repository.OrderOutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrderOutboxAdapter implements OrderOutboxRepository {

	private final OrderOutboxPersistence orderOutboxPersistence;

	@Override
	public OrderOutbox save(OrderOutbox orderOutbox) {
		return orderOutboxPersistence.save(orderOutbox);
	}
}
