package com.prompthub.order.application.service.order;

import com.prompthub.order.application.dto.CreateOrderResult;
import com.prompthub.order.application.event.order.OrderCreatedEvent;
import com.prompthub.order.application.event.order.OrderProductReservationCleanupEvent;
import com.prompthub.order.application.service.event.OrderPaidOutboxAppender;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.repository.CartRepository;
import com.prompthub.order.domain.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderCreationTransactionService {

	private final OrderRepository orderRepository;
	private final CartRepository cartRepository;
	private final ApplicationEventPublisher applicationEventPublisher;
	private final OrderPaidOutboxAppender orderPaidOutboxAppender;
	private final OrderProductPurchasePolicy purchasePolicy;

	@Transactional
	public CreateOrderResult create(Order order) {
		List<UUID> productIds = order.getOrderProducts().stream()
			.map(OrderProduct::getProductId)
			.distinct()
			.sorted()
			.toList();
		purchasePolicy.validateOrderable(order.getBuyerId(), productIds);

		Order savedOrder = orderRepository.saveAndFlush(order);
		removeOrderedProductsFromCart(savedOrder);
		if (savedOrder.isFree()) {
			orderPaidOutboxAppender.append(savedOrder);
			applicationEventPublisher.publishEvent(
				OrderProductReservationCleanupEvent.from(savedOrder)
			);
		} else {
			applicationEventPublisher.publishEvent(OrderCreatedEvent.from(savedOrder));
		}
		return CreateOrderResult.from(savedOrder);
	}

	private void removeOrderedProductsFromCart(Order order) {
		List<UUID> productIds = order.getOrderProducts().stream()
			.map(OrderProduct::getProductId)
			.distinct()
			.sorted()
			.toList();
		cartRepository.findByBuyerIdForUpdateWithCartProducts(order.getBuyerId())
			.ifPresent(cart -> {
				cart.removeProductsByProductIds(productIds);
				cartRepository.save(cart);
			});
	}
}
