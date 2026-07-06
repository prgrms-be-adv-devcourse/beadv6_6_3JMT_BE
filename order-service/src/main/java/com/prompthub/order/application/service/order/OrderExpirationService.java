package com.prompthub.order.application.service.order;

import com.prompthub.order.domain.model.Cart;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.repository.CartRepository;
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
	private final CartRepository cartRepository;
	private final OrderExpirationPolicy expirationPolicy;

	public boolean cancelPendingOrderByTimeout(UUID orderId, LocalDateTime now) {
		return orderRepository.findByIdWithOrderProducts(orderId)
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
		restoreCart(order);
		return true;
	}

	private void restoreCart(Order order) {
		Cart cart = cartRepository.findByBuyerIdWithCartProducts(order.getBuyerId())
			.orElseGet(() -> Cart.create(order.getBuyerId()));

		order.getOrderProducts().stream()
			.map(OrderProduct::getProductId)
			.filter(productId -> !cart.containsProduct(productId))
			.forEach(cart::addProduct);

		cartRepository.save(cart);
	}
}
