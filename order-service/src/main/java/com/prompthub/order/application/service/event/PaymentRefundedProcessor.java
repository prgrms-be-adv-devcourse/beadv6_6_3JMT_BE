package com.prompthub.order.application.service.event;

import com.prompthub.order.application.dto.event.PaymentRefundFailedCommand;
import com.prompthub.order.application.dto.event.PaymentRefundedCommand;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
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
	private final OrderOutboxAppender orderOutboxAppender;
	private final PaymentEventValidator validator;

	@Transactional
	public void process(UUID eventId, String eventType, LocalDateTime occurredAt, PaymentRefundedCommand command) {
		validator.validateEnvelope(eventId, eventType, occurredAt);
		LocalDateTime refundedAt = validator.validate(command);
		if (processedEventService.isProcessed(eventId, CONSUMER_GROUP)) {
			return;
		}

		Order order = orderRepository.findByIdWithOrderProductsForUpdate(command.orderId())
			.orElseThrow(() -> new OrderException(ErrorCode.ORDER_NOT_FOUND));
		if (processedEventService.isProcessed(eventId, CONSUMER_GROUP)) {
			return;
		}
		List<OrderProduct> refundedProducts = order.completeRequestedRefund(command.refundAmount(), refundedAt);

		orderOutboxAppender.appendRefunded(order, refundedProducts, refundedAt);
		processedEventService.markProcessed(eventId, CONSUMER_GROUP, eventType, occurredAt);

		log.info(
			"결제 환불 이벤트 처리 완료. eventId={}, orderId={}, refundAmount={}, status={}",
			eventId, order.getId(), command.refundAmount(), order.getOrderStatus()
		);
	}

	@Transactional
	public void processFailed(
		UUID eventId,
		String eventType,
		LocalDateTime occurredAt,
		PaymentRefundFailedCommand command
	) {
		validator.validateEnvelope(eventId, eventType, occurredAt);
		LocalDateTime failedAt = validator.validate(command);
		if (processedEventService.isProcessed(eventId, CONSUMER_GROUP)) {
			return;
		}

		Order order = orderRepository.findByIdWithOrderProductsForUpdate(command.orderId())
			.orElseThrow(() -> new OrderException(ErrorCode.ORDER_NOT_FOUND));
		if (processedEventService.isProcessed(eventId, CONSUMER_GROUP)) {
			return;
		}
		order.validateRequestedRefundAmount(command.refundAmount());
		order.restoreRequestedRefund();
		orderOutboxAppender.appendRefundFailed(order, command.refundAmount(), failedAt);
		processedEventService.markProcessed(eventId, CONSUMER_GROUP, eventType, occurredAt);

		log.warn(
			"결제 환불 실패 이벤트 처리 완료. eventId={}, orderId={}, refundAmount={}, failedAt={}",
			eventId, command.orderId(), command.refundAmount(), failedAt
		);
	}
}
