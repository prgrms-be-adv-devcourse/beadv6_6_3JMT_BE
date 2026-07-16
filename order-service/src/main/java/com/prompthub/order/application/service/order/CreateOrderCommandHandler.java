package com.prompthub.order.application.service.order;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.application.client.ProductClient;
import com.prompthub.order.application.dto.ProductOrderSnapshot;
import com.prompthub.order.application.event.order.OrderCreatedEvent;
import com.prompthub.order.application.service.event.OrderEventMessageFactory;
import com.prompthub.order.application.service.event.outbox.OutboxEventAppender;
import com.prompthub.order.application.usecase.CreateOrderUseCase;
import com.prompthub.order.domain.model.Cart;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.repository.CartRepository;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.infra.messaging.kafka.event.OrderCreatedPayload;
import com.prompthub.order.presentation.dto.request.CreateOrderRequest;
import com.prompthub.order.presentation.dto.response.CreateOrderResponse;
import com.prompthub.order.presentation.dto.response.OrderProductsResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
public class CreateOrderCommandHandler implements CreateOrderUseCase {

	private final OrderRepository orderRepository;
	private final CartRepository cartRepository;
	private final OrderNumberGenerator orderNumberGenerator;
	private final ProductClient productClient;
	private final OrderPolicyService orderPolicyService;
	private final OrderEventMessageFactory orderEventMessageFactory;
	private final OutboxEventAppender outboxEventAppender;
	private final ApplicationEventPublisher applicationEventPublisher;

	@Override
	public CreateOrderResponse createOrder(UUID buyerId, CreateOrderRequest request) {
		orderPolicyService.validateCreateOrderRequest(request);

		List<ProductOrderSnapshot> products = productClient.getOrderSnapshots(request.productIds());
		orderPolicyService.validateProductSnapshots(request.productIds(), products);

		int totalAmount = products.stream().mapToInt(ProductOrderSnapshot::amount).sum();
		int totalCount = products.size();

		String orderNumber = orderNumberGenerator.generate();
		Order order = Order.create(buyerId, products.getFirst().sellerId(), orderNumber, totalAmount);
		products.stream()
			.map(product -> OrderProduct.create(
				product.productId(),
				product.sellerId(),
				product.title(),
				product.productType(),
				product.model(),
				product.amount()
			))
			.forEach(order::addOrderProduct);

		Order savedOrder = orderRepository.save(order);
		removeOrderedProductsFromCart(buyerId, request.productIds());

		OrderCreatedPayload payload = OrderCreatedPayload.from(savedOrder);
		EventMessage<OrderCreatedPayload> message = orderEventMessageFactory.createOrderCreatedMessage(
			savedOrder.getId(),
			payload
		);
		outboxEventAppender.append(message);

		applicationEventPublisher.publishEvent(new OrderCreatedEvent(savedOrder.getId(), savedOrder.getCreatedAt()));

		List<OrderProductsResponse> responseProducts = savedOrder.getOrderProducts().stream()
			.map(product -> new OrderProductsResponse(
				product.getId(),
				product.getProductId(),
				product.getSellerId(),
				product.getProductTitle(),
				product.getProductType(),
				product.getProductModel(),
				product.getProductAmount(),
				product.getOrderStatus()
			))
			.toList();

		return new CreateOrderResponse(
			savedOrder.getId(),
			savedOrder.getOrderNumber(),
			savedOrder.getBuyerId(),
			savedOrder.getOrderStatus(),
			responseProducts,
			savedOrder.getTotalOrderAmount(),
			savedOrder.getCreatedAt(),
			savedOrder.getCanceledAt()
		);
	}

	private void removeOrderedProductsFromCart(UUID buyerId, List<UUID> productIds) {
		cartRepository.findByBuyerIdWithCartProducts(buyerId)
			.ifPresent(cart -> removeOrderedProducts(cart, productIds));
	}

	private void removeOrderedProducts(Cart cart, List<UUID> productIds) {
		cart.removeProductsByProductIds(productIds);
		cartRepository.save(cart);
	}
}
