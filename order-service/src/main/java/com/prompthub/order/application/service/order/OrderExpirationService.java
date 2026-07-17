package com.prompthub.order.application.service.order;

import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
public class OrderExpirationService {

	private final OrderRepository orderRepository;
	private final OrderExpirationPolicy expirationPolicy;

	public boolean cancelPendingOrderByTimeout(UUID orderId, LocalDateTime now) {
		return orderRepository.findByIdWithOrderProductsForUpdate(orderId)
			.map(order -> cancelIfExpired(order, now))
			.orElse(true);
	}

	private boolean cancelIfExpired(Order order, LocalDateTime now) {
		if (!order.isPending()) {
			return true;
		}

		if (!order.isExpired(now, expirationPolicy.paymentTimeoutMinutes())) {
			return false;
		}

		order.expirePending(now);
		return true;
	}
}
