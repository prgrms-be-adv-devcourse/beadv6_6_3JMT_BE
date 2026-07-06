package com.prompthub.order.domain.repository;

import com.prompthub.order.application.dto.OrderListProjection;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository {
	Order save (Order order);

	Optional<Order> findByIdWithOrderProducts(UUID orderId);

	Optional<Order> findByOrderNumber(String orderNumber);

	boolean existsPaidOrderProductByBuyerIdAndProductId(UUID buyerId, UUID productId);

	Page<OrderListProjection> searchOrderproducts(
		UUID buyerId,
		OrderStatus status,
		LocalDateTime from,
		LocalDateTime to,
		Pageable pageable
	);
}
