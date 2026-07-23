package com.prompthub.order.application.service.order;

import com.prompthub.order.application.dto.CreateOrderResult;
import com.prompthub.order.application.dto.OrderCreationItem;
import com.prompthub.order.application.event.order.OrderCreatedEvent;
import com.prompthub.order.application.service.event.OrderPaidOutboxAppender;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.repository.CartRepository;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderCreator {

	private final OrderRepository orderRepository;
	private final CartRepository cartRepository;
	private final OrderNumberGenerator orderNumberGenerator;
	private final ApplicationEventPublisher applicationEventPublisher;
	private final OrderPaidOutboxAppender orderPaidOutboxAppender;

	@Transactional
	public CreateOrderResult create(UUID buyerId, List<OrderCreationItem> items) {
		int totalAmount = OrderAmountCalculator.sum(items, OrderCreationItem::amount);
		validateNoAccessibleFreeProduct(buyerId, items);
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

		Order savedOrder = orderRepository.save(order);
		removeOrderedProductsFromCart(buyerId, savedOrder);
		if (savedOrder.isFree()) {
			orderPaidOutboxAppender.append(savedOrder);
		} else {
			applicationEventPublisher.publishEvent(OrderCreatedEvent.from(savedOrder));
		}

		return CreateOrderResult.from(savedOrder);
	}

	private void validateNoAccessibleFreeProduct(UUID buyerId, List<OrderCreationItem> items) {
		Set<UUID> requestedFreeProductIds = items.stream()
			.filter(item -> item.amount() == 0)
			.map(OrderCreationItem::productId)
			.collect(java.util.stream.Collectors.toSet());
		if (requestedFreeProductIds.isEmpty()) {
			return;
		}

		Set<UUID> accessibleProductIds = new HashSet<>(
			orderRepository.findAccessiblePaidProductIdsByBuyerId(buyerId)
		);
		if (requestedFreeProductIds.stream().anyMatch(accessibleProductIds::contains)) {
			throw new OrderException(ErrorCode.ORDER_PRODUCT_ALREADY_OWNED);
		}
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
