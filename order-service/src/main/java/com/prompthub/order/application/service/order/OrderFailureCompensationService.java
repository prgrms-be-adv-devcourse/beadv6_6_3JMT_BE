package com.prompthub.order.application.service.order;

import com.prompthub.order.application.event.order.OrderExpirationCleanupRequestedEvent;
import com.prompthub.order.application.service.event.ProcessedEventService;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Cart;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.repository.CartRepository;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.infra.messaging.kafka.event.PaymentFailedPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderFailureCompensationService {

	private static final String CONSUMER_GROUP = "order-service";

	private final OrderRepository orderRepository;
	private final CartRepository cartRepository;
	private final ProcessedEventService processedEventService;
	private final OrderExpirationPolicy expirationPolicy;
	private final ApplicationEventPublisher eventPublisher;

	@Transactional
	public void compensatePaymentFailure(
		UUID eventId,
		String eventType,
		LocalDateTime occurredAt,
		PaymentFailedPayload payload
	) {
		LocalDateTime failedAt = validateFailureEvent(eventId, eventType, occurredAt, payload);
		if (processedEventService.isProcessed(eventId, CONSUMER_GROUP)) {
			publishCleanup(payload.orderId());
			return;
		}

		Order order = orderRepository.findByIdWithOrderProductsForUpdate(payload.orderId())
			.orElseThrow(() -> new OrderException(ErrorCode.ORDER_NOT_FOUND));

		if (processedEventService.isProcessed(eventId, CONSUMER_GROUP)) {
			publishCleanup(order.getId());
			return;
		}
		if (!order.getBuyerId().equals(payload.buyerId())) {
			throw new OrderException(ErrorCode.ORDER_ACCESS_DENIED);
		}

		CompensationResult result = compensateCreatedOrder(order, failedAt);
		processedEventService.markProcessed(eventId, CONSUMER_GROUP, eventType, occurredAt);
		publishCleanup(order.getId());
		logPaymentFailure(eventId, payload, failedAt, order, result);
	}

	@Transactional
	public boolean compensateTimeout(UUID orderId, LocalDateTime timedOutAt) {
		validateTimeout(orderId, timedOutAt);
		Optional<Order> lockedOrder = orderRepository.findByIdWithOrderProductsForUpdate(orderId);
		if (lockedOrder.isEmpty()) {
			publishCleanup(orderId);
			return true;
		}

		Order order = lockedOrder.get();
		if (order.getOrderStatus() == OrderStatus.CREATED
			&& !order.isExpired(timedOutAt, expirationPolicy.paymentTimeoutMinutes())) {
			log.info(
				"결제 제한 시간이 지나지 않아 주문 보상을 건너뜁니다. orderId={}, timedOutAt={}, createdAt={}",
				orderId,
				timedOutAt,
				order.getCreatedAt()
			);
			return false;
		}
		CompensationResult result = compensateCreatedOrder(order, timedOutAt);
		publishCleanup(orderId);
		log.info(
			"결제 결과 미수신 주문 보상 완료. orderId={}, timedOutAt={}, beforeStatus={}, afterStatus={}, targetCount={}, addedCount={}",
			orderId,
			timedOutAt,
			result.beforeStatus(),
			order.getOrderStatus(),
			result.targetCount(),
			result.addedCount()
		);
		return true;
	}

	private CompensationResult compensateCreatedOrder(Order order, LocalDateTime compensatedAt) {
		OrderStatus beforeStatus = order.getOrderStatus();
		if (beforeStatus != OrderStatus.CREATED) {
			return new CompensationResult(beforeStatus, 0, 0);
		}

		List<UUID> productIds = order.getOrderProducts().stream()
			.map(OrderProduct::getProductId)
			.distinct()
			.sorted()
			.toList();
		Cart cart = cartRepository.findByBuyerIdForUpdateWithCartProducts(order.getBuyerId())
			.orElseGet(() -> Cart.create(order.getBuyerId()));
		int addedCount = cart.addProductsIfAbsent(productIds);
		cartRepository.save(cart);
		order.markFailed(compensatedAt);

		return new CompensationResult(beforeStatus, productIds.size(), addedCount);
	}

	private LocalDateTime validateFailureEvent(
		UUID eventId,
		String eventType,
		LocalDateTime occurredAt,
		PaymentFailedPayload payload
	) {
		if (eventId == null
			|| eventType == null
			|| eventType.isBlank()
			|| occurredAt == null
			|| payload == null
			|| payload.paymentId() == null
			|| payload.orderId() == null
			|| payload.buyerId() == null) {
			throw invalidInput();
		}

		try {
			return payload.failedAtOr(occurredAt);
		} catch (DateTimeException | IllegalArgumentException exception) {
			throw invalidInput();
		}
	}

	private void validateTimeout(UUID orderId, LocalDateTime timedOutAt) {
		if (orderId == null || timedOutAt == null) {
			throw invalidInput();
		}
	}

	private OrderException invalidInput() {
		return new OrderException(ErrorCode.INVALID_INPUT_VALUE);
	}

	private void publishCleanup(UUID orderId) {
		eventPublisher.publishEvent(new OrderExpirationCleanupRequestedEvent(orderId));
	}

	private void logPaymentFailure(
		UUID eventId,
		PaymentFailedPayload payload,
		LocalDateTime failedAt,
		Order order,
		CompensationResult result
	) {
		log.info(
			"결제 실패 주문 보상 완료. eventId={}, paymentId={}, orderId={}, failedAt={}, beforeStatus={}, afterStatus={}, targetCount={}, addedCount={}, consumerGroup={}",
			eventId,
			payload.paymentId(),
			order.getId(),
			failedAt,
			result.beforeStatus(),
			order.getOrderStatus(),
			result.targetCount(),
			result.addedCount(),
			CONSUMER_GROUP
		);
		if (hasText(payload.failureCode()) || hasText(payload.failureReason())) {
			log.info(
				"결제 실패 상세. eventId={}, orderId={}, failureCode={}, failureReason={}",
				eventId,
				order.getId(),
				payload.failureCode(),
				payload.failureReason()
			);
		}
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private record CompensationResult(OrderStatus beforeStatus, int targetCount, int addedCount) {
	}
}
