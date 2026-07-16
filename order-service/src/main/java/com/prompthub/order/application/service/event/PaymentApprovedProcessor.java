package com.prompthub.order.application.service.event;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.application.event.order.OrderPaidEvent;
import com.prompthub.order.application.service.event.outbox.OutboxEventAppender;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.repository.CartRepository;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.infra.messaging.kafka.event.OrderPaidPayload;
import com.prompthub.order.infra.messaging.kafka.event.PaymentApprovedOrderPayload;
import com.prompthub.order.infra.messaging.kafka.event.PaymentApprovedPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentApprovedProcessor {

	private static final String CONSUMER_GROUP = "order-service";

	private final ProcessedEventService processedEventService;
	private final OrderRepository orderRepository;
	private final CartRepository cartRepository;
	private final OrderEventMessageFactory orderEventMessageFactory;
	private final OutboxEventAppender outboxEventAppender;
	private final PaymentEventValidator validator;
	private final ApplicationEventPublisher applicationEventPublisher;

	@Transactional
	public void process(
		UUID eventId,
		String eventType,
		LocalDateTime occurredAt,
		PaymentApprovedPayload payload
	) {
		validator.validateEnvelope(eventId, eventType, occurredAt);
		if (processedEventService.isProcessed(eventId, CONSUMER_GROUP)) {
			return;
		}

		List<UUID> orderIds = validator.validate(payload);
		List<Order> orders = orderRepository.findAllByIdsWithOrderProductsForUpdate(orderIds);

		if (processedEventService.isProcessed(eventId, CONSUMER_GROUP)) {
			return;
		}
		validateAllOrdersLoaded(orderIds, orders);
		validateApprovalTargets(payload, orders);

		List<Order> completedOrders = new ArrayList<>();
		for (Order order : orders) {
			if (order.getOrderStatus() == OrderStatus.CREATED
				|| order.getOrderStatus() == OrderStatus.FAILED) {
				order.markCompleted(payload.approvedAt());
				completedOrders.add(order);
				EventMessage<OrderPaidPayload> message = orderEventMessageFactory.createOrderPaidMessage(
					order.getId(),
					OrderPaidPayload.from(order)
				);
				outboxEventAppender.append(message);
			}
		}

		List<UUID> productIds = orders.stream()
			.flatMap(order -> order.getOrderProducts().stream())
			.map(OrderProduct::getProductId)
			.distinct()
			.sorted()
			.toList();
		cartRepository.findByBuyerIdWithCartProducts(payload.buyerId())
			.ifPresent(cart -> cart.removeProductsByProductIds(productIds));

		processedEventService.markProcessed(eventId, CONSUMER_GROUP, eventType, occurredAt);
		if (!completedOrders.isEmpty()) {
			applicationEventPublisher.publishEvent(OrderPaidEvent.from(completedOrders));
		}

		log.info(
			"결제 승인 이벤트 처리 완료. eventId={}, paymentId={}, orderIds={}, statuses={}, consumerGroup={}",
			eventId,
			payload.paymentId(),
			orderIds,
			orders.stream().map(order -> order.getId() + ":" + order.getOrderStatus()).toList(),
			CONSUMER_GROUP
		);
	}

	private void validateAllOrdersLoaded(List<UUID> orderIds, List<Order> orders) {
		Set<UUID> loadedOrderIds = orders.stream()
			.map(Order::getId)
			.collect(Collectors.toSet());
		if (orders.size() != orderIds.size() || !loadedOrderIds.equals(Set.copyOf(orderIds))) {
			throw new OrderException(ErrorCode.ORDER_NOT_FOUND);
		}
	}

	private void validateApprovalTargets(PaymentApprovedPayload payload, List<Order> orders) {
		Map<UUID, Order> ordersById = orders.stream()
			.collect(Collectors.toMap(Order::getId, Function.identity()));
		for (PaymentApprovedOrderPayload target : payload.orders()) {
			Order order = ordersById.get(target.orderId());
			if (order == null || !order.getBuyerId().equals(payload.buyerId())) {
				throw new OrderException(ErrorCode.INVALID_INPUT_VALUE);
			}
			Set<UUID> actualOrderProductIds = order.getOrderProducts().stream()
				.map(OrderProduct::getId)
				.collect(Collectors.toSet());
			if (!actualOrderProductIds.containsAll(target.orderProductIds())) {
				throw new OrderException(ErrorCode.INVALID_INPUT_VALUE);
			}
		}
	}

}
