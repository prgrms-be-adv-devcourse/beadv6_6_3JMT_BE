package com.prompthub.order.application.service.event;

import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.infra.messaging.kafka.event.PaymentFailedPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static java.util.stream.Collectors.toSet;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentFailedProcessor {

	private static final String CONSUMER_GROUP = "order-service";

	private final ProcessedEventService processedEventService;
	private final OrderRepository orderRepository;
	private final PaymentEventValidator validator;

	@Transactional
	public void process(
		UUID eventId,
		String eventType,
		LocalDateTime occurredAt,
		PaymentFailedPayload payload
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

		orders.stream()
			.filter(order -> order.getOrderStatus() == OrderStatus.CREATED)
			.forEach(Order::markFailed);

		processedEventService.markProcessed(eventId, CONSUMER_GROUP, eventType, occurredAt);

		log.info(
			"결제 실패 이벤트 처리 완료. eventId={}, paymentId={}, orderIds={}, failureCode={}, "
				+ "failureReason={}, failedAt={}, statuses={}, consumerGroup={}",
			eventId,
			payload.paymentId(),
			orderIds,
			payload.failureCode(),
			payload.failureReason(),
			payload.failedAt(),
			orders.stream().map(order -> order.getId() + ":" + order.getOrderStatus()).toList(),
			CONSUMER_GROUP
		);
	}

	private void validateAllOrdersLoaded(List<UUID> orderIds, List<Order> orders) {
		Set<UUID> loadedOrderIds = orders.stream()
			.map(Order::getId)
			.collect(toSet());
		if (orders.size() != orderIds.size() || !loadedOrderIds.equals(Set.copyOf(orderIds))) {
			throw new OrderException(ErrorCode.ORDER_NOT_FOUND);
		}
	}
}
