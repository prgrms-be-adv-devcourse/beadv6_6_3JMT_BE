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
import java.util.UUID;

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
		validator.validate(payload);
		if (processedEventService.isProcessed(eventId, CONSUMER_GROUP)) {
			return;
		}

		Order order = orderRepository.findByIdWithOrderProductsForUpdate(payload.orderId())
			.orElseThrow(() -> new OrderException(ErrorCode.ORDER_NOT_FOUND));

		if (processedEventService.isProcessed(eventId, CONSUMER_GROUP)) {
			return;
		}
		if (!order.getBuyerId().equals(payload.userId())) {
			throw new OrderException(ErrorCode.ORDER_ACCESS_DENIED);
		}

		boolean transitioned = order.getOrderStatus() == OrderStatus.CREATED;
		if (transitioned) {
			order.markFailed();
		}

		processedEventService.markProcessed(eventId, CONSUMER_GROUP, eventType, occurredAt);

		log.info(
			"결제 실패 이벤트 처리 완료. eventId={}, paymentId={}, orderId={}, status={}, transitioned={}, consumerGroup={}",
			eventId,
			payload.paymentId(),
			order.getId(),
			order.getOrderStatus(),
			transitioned,
			CONSUMER_GROUP
		);
	}
}
