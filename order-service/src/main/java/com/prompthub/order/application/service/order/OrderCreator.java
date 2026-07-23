package com.prompthub.order.application.service.order;

import com.prompthub.order.application.dto.CreateOrderResult;
import com.prompthub.order.application.dto.OrderItem;
import com.prompthub.order.application.event.order.OrderCreatedEvent;
import com.prompthub.order.application.event.order.OrderProductReservationCleanupRequestedEvent;
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
public class OrderCreator {

	private final OrderRepository orderRepository;
	private final CartRepository cartRepository;
	private final OrderNumberGenerator orderNumberGenerator;
	private final ApplicationEventPublisher applicationEventPublisher;
	private final OrderPaidOutboxAppender orderPaidOutboxAppender;
	private final OrderProductPurchasePolicy purchasePolicy;
	private final OrderProductReservationService reservationService;

	@Transactional
	public CreateOrderResult create(UUID buyerId, List<OrderItem> items) {
		int totalAmount = OrderAmountCalculator.sum(items, OrderItem::amount);
		List<UUID> productIds = items.stream()
			.map(OrderItem::productId)
			.toList();
		purchasePolicy.validateOrderable(buyerId, productIds);
		Order order = Order.create(buyerId, orderNumberGenerator.generate(), totalAmount);
		items.stream()
			.map(item -> OrderProduct.create(
				item.productId(),
				item.sellerId(),
				item.productTitle(),
				item.amount()
			))
			.forEach(order::addOrderProduct);
		if (order.isFree()) {
			order.completeFreeOrder();
		}
		reservationService.reserve(order);

		Order savedOrder = orderRepository.save(order);
		removeOrderedProductsFromCart(buyerId, savedOrder);
		if (savedOrder.isFree()) {
			orderPaidOutboxAppender.append(savedOrder);
			applicationEventPublisher.publishEvent(OrderProductReservationCleanupRequestedEvent.from(savedOrder));
		} else {
			applicationEventPublisher.publishEvent(OrderCreatedEvent.from(savedOrder));
		}

		return CreateOrderResult.from(savedOrder);
	}

	private void removeOrderedProductsFromCart(UUID buyerId, Order order) {
		List<UUID> productIds = order.getOrderProducts().stream()
			.map(OrderProduct::getProductId)
			.toList();

		cartRepository.findByBuyerIdForUpdateWithCartProducts(buyerId)
			.ifPresent(cart -> {
				cart.removeProductsByProductIds(productIds);
				cartRepository.save(cart);
			});
	}
}
