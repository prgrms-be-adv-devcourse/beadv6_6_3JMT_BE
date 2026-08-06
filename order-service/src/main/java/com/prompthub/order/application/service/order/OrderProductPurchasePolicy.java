package com.prompthub.order.application.service.order;

import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderProductPurchasePolicy {

	private final OrderRepository orderRepository;
	private final OrderProductReservationService reservationService;

	public void validateOrderable(UUID buyerId, Collection<UUID> productIds) {
		boolean blocked = productIds.stream()
			.distinct()
			.anyMatch(productId ->
				orderRepository.existsBlockingOrderProductByBuyerIdAndProductId(buyerId, productId)
			);
		if (blocked) {
			throw alreadyOwned();
		}
	}

	public void validateCartAddable(UUID buyerId, UUID productId) {
		if (orderRepository.existsBlockingOrderProductByBuyerIdAndProductId(buyerId, productId)
			|| reservationService.isReserved(buyerId, productId)) {
			throw alreadyOwned();
		}
	}

	private OrderException alreadyOwned() {
		return new OrderException(ErrorCode.ORDER_PRODUCT_ALREADY_OWNED);
	}
}
