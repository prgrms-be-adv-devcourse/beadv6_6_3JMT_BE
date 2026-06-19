package com.prompthub.order.domain.repository;

import com.prompthub.order.domain.model.Order;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface OrderRepository {
	Order save (Order order);

	Optional<Order> findByIdWithOrderProducts(UUID orderId);
}
