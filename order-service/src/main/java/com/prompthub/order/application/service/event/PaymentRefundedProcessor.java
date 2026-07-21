package com.prompthub.order.application.service.event;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.application.service.event.outbox.OutboxEventAppender;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.infra.messaging.kafka.event.OrderRefundPayload;
import com.prompthub.order.infra.messaging.kafka.event.PaymentRefundedPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentRefundedProcessor {

	private static final String CONSUMER_GROUP = "order-service";

	private final ProcessedEventService processedEventService;
	private final OrderRepository orderRepository;
	private final OrderEventMessageFactory orderEventMessageFactory;
	private final OutboxEventAppender outboxEventAppender;
	private final PaymentEventValidator validator;

	@Transactional
	public void process(UUID eventId, String eventType, LocalDateTime occurredAt, PaymentRefundedPayload payload) {
		validator.validateEnvelope(eventId, eventType, occurredAt);
		LocalDateTime refundedAt = validator.validate(payload);
		if (processedEventService.isProcessed(eventId, CONSUMER_GROUP)) {
			return;
		}

		Order order = orderRepository.findByIdWithOrderProductsForUpdate(payload.orderId())
			.orElseThrow(() -> new OrderException(ErrorCode.ORDER_NOT_FOUND));
		if (processedEventService.isProcessed(eventId, CONSUMER_GROUP)) {
			return;
		}
		List<OrderProduct> refundedProducts = order.completeRequestedRefund(payload.refundAmount(), refundedAt);

		OrderRefundPayload orderRefundPayload = OrderRefundPayload.from(order, refundedProducts, refundedAt);
		EventMessage<OrderRefundPayload> orderRefundMessage =
			orderEventMessageFactory.createOrderRefundMessage(order.getId(), orderRefundPayload);
		outboxEventAppender.append(orderRefundMessage);
		processedEventService.markProcessed(eventId, CONSUMER_GROUP, eventType, occurredAt);

		log.info(
			"결제 환불 이벤트 처리 완료. eventId={}, orderId={}, refundAmount={}, status={}",
			eventId, order.getId(), payload.refundAmount(), order.getOrderStatus()
		);
	}

	@Transactional
	public void processFailed(
		UUID eventId,
		String eventType,
		LocalDateTime occurredAt,
		PaymentRefundedEventHandler.RefundFailedPayload payload
	) {
		validator.validateEnvelope(eventId, eventType, occurredAt);
		LocalDateTime failedAt = validator.validate(payload);
		if (processedEventService.isProcessed(eventId, CONSUMER_GROUP)) {
			return;
		}

		Order order = orderRepository.findByIdWithOrderProductsForUpdate(payload.orderId())
			.orElseThrow(() -> new OrderException(ErrorCode.ORDER_NOT_FOUND));
		if (processedEventService.isProcessed(eventId, CONSUMER_GROUP)) {
			return;
		}
		order.validateRequestedRefundAmount(payload.refundAmount());
		processedEventService.markProcessed(eventId, CONSUMER_GROUP, eventType, occurredAt);

		log.warn(
			"결제 환불 실패 이벤트 처리 완료. eventId={}, orderId={}, refundAmount={}, failedAt={}",
			eventId, payload.orderId(), payload.refundAmount(), failedAt
		);
	}
}
