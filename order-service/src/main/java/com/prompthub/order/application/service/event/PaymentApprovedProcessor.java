package com.prompthub.order.application.service.event;

import com.prompthub.order.application.event.order.OrderExpirationCleanupRequestedEvent;
import com.prompthub.order.application.event.order.OrderProductReservationCleanupEvent;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.repository.CartRepository;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.infra.messaging.kafka.event.PaymentApprovedPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentApprovedProcessor {

	private static final String CONSUMER_GROUP = "order-service";

	private final ProcessedEventService processedEventService;
	private final OrderRepository orderRepository;
	private final CartRepository cartRepository;
	private final OrderPaidOutboxAppender orderPaidOutboxAppender;
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
		LocalDateTime approvedAt = validator.validate(payload);
		if (processedEventService.isProcessed(eventId, CONSUMER_GROUP)) {
			publishExpirationCleanup(payload.orderId());
			return;
		}

		Order order = orderRepository.findByIdWithOrderProductsForUpdate(payload.orderId())
			.orElseThrow(() -> new OrderException(ErrorCode.ORDER_NOT_FOUND));

		if (processedEventService.isProcessed(eventId, CONSUMER_GROUP)) {
			publishExpirationCleanup(order.getId());
			return;
		}
		boolean transitioned = order.getOrderStatus() == OrderStatus.CREATED
			|| order.getOrderStatus() == OrderStatus.FAILED;
		if (transitioned) {
			order.markCompleted(approvedAt);
			removePurchasedProductsFromCart(order.getBuyerId(), order);
			orderPaidOutboxAppender.append(order);
		}

		processedEventService.markProcessed(eventId, CONSUMER_GROUP, eventType, occurredAt);
		publishCleanup(order);

		log.info(
			"결제 승인 이벤트 처리 완료. eventId={}, orderId={}, status={}, transitioned={}, consumerGroup={}",
			eventId,
			order.getId(),
			order.getOrderStatus(),
			transitioned,
			CONSUMER_GROUP
		);
	}

	private void publishExpirationCleanup(UUID orderId) {
		applicationEventPublisher.publishEvent(new OrderExpirationCleanupRequestedEvent(orderId));
	}

	private void publishCleanup(Order order) {
		publishExpirationCleanup(order.getId());
		applicationEventPublisher.publishEvent(OrderProductReservationCleanupEvent.from(order));
	}

	private void removePurchasedProductsFromCart(UUID buyerId, Order order) {
		List<UUID> productIds = order.getOrderProducts().stream()
			.map(OrderProduct::getProductId)
			.distinct()
			.sorted()
			.toList();
		cartRepository.findByBuyerIdForUpdateWithCartProducts(buyerId)
			.ifPresent(cart -> {
				cart.removeProductsByProductIds(productIds);
				cartRepository.save(cart);
			});
	}
}
