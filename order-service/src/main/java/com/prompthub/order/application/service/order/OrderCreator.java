package com.prompthub.order.application.service.order;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.application.dto.CreateOrderResult;
import com.prompthub.order.application.dto.OrderItem;
import com.prompthub.order.application.event.order.OrderCreatedEvent;
import com.prompthub.order.application.service.event.OrderEventMessageFactory;
import com.prompthub.order.application.service.event.outbox.OutboxEventAppender;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.infra.messaging.kafka.event.OrderCreatedPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;

@Service
@RequiredArgsConstructor
public class OrderCreator {

	private final OrderRepository orderRepository;
	private final OrderNumberGenerator orderNumberGenerator;
	private final OrderEventMessageFactory orderEventMessageFactory;
	private final OutboxEventAppender outboxEventAppender;
	private final ApplicationEventPublisher applicationEventPublisher;

	@Transactional
	public CreateOrderResult create(UUID buyerId, List<OrderItem> items) {
		Map<UUID, List<OrderItem>> itemsBySeller = items.stream()
			.collect(groupingBy(OrderItem::sellerId, LinkedHashMap::new, toList()));
		List<Order> orders = itemsBySeller.entrySet().stream()
			.map(entry -> createOrder(buyerId, entry.getKey(), entry.getValue()))
			.toList();

		List<Order> savedOrders = orderRepository.saveAll(orders);
		OrderCreatedPayload payload = OrderCreatedPayload.from(buyerId, savedOrders);
		EventMessage<OrderCreatedPayload> message = orderEventMessageFactory.createOrderCreatedMessage(payload);
		outboxEventAppender.append(message);
		applicationEventPublisher.publishEvent(OrderCreatedEvent.from(savedOrders));

		return CreateOrderResult.from(savedOrders);
	}

	private Order createOrder(UUID buyerId, UUID sellerId, List<OrderItem> items) {
		int totalAmount = items.stream().mapToInt(OrderItem::amount).sum();
		Order order = Order.create(
			buyerId,
			sellerId,
			orderNumberGenerator.generate(),
			totalAmount
		);
		items.stream()
			.map(item -> OrderProduct.create(item.productId(), item.productTitle(), item.amount()))
			.forEach(order::addOrderProduct);
		return order;
	}
}
