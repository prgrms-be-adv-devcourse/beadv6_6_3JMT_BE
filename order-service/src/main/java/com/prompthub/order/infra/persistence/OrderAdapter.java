package com.prompthub.order.infra.persistence;

import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class OrderAdapter implements OrderRepository {
	private final OrderPersistence orderPersistence;

	@Override
	public Order save(Order order) {
		return orderPersistence.save(order);
	}

	@Override
	public Optional<Order> findByIdWithOrderProducts(UUID orderId) {
		return orderPersistence.findByIdWithOrderProducts(orderId);
	}
}
