package com.prompthub.order.infra.persistence;

import com.prompthub.order.application.dto.OrderListProjection;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
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

	@Override
	public boolean existsPaidOrderProductByBuyerIdAndProductId(UUID buyerId, UUID productId) {
		return orderPersistence.existsPaidOrderProductByBuyerIdAndProductId(buyerId, productId);
	}

	@Override
	public Page<OrderListProjection> searchOrderproducts(
		UUID buyerId,
		OrderStatus status,
		LocalDateTime from,
		LocalDateTime to,
		Pageable pageable
	) {
		return orderPersistence.searchOrderProducts(buyerId, status, from, to, pageable);
	}
}
