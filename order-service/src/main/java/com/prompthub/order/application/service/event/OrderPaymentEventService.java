package com.prompthub.order.application.service.event;

import com.prompthub.order.application.event.payment.PaymentApprovedEvent;
import com.prompthub.order.application.event.payment.PaymentRefundedEvent;
import com.prompthub.order.application.service.event.outbox.OutboxEventAppender;
import com.prompthub.order.application.service.order.OrderExpirationStore;
import com.prompthub.order.application.service.order.OrderPolicyService;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderPayment;
import com.prompthub.order.domain.repository.OrderPaymentRepository;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class OrderPaymentEventService {

	private final OrderRepository orderRepository;
	private final OrderPaymentRepository orderPaymentRepository;
	private final OutboxEventAppender outboxEventAppender;
	private final OrderPolicyService orderPolicyService;
	private final OrderExpirationStore orderExpirationStore;

	public void handlePaymentApproved(PaymentApprovedEvent event) {
		Order order = findOrder(event.orderId());
		boolean orderPaymentExists = orderPaymentRepository.existsByPaymentId(event.paymentId());

		if (order.getOrderStatus() == OrderStatus.PAID && orderPaymentExists) {
			return;
		}

		validatePendingForPaymentApproval(order);
		orderPolicyService.validatePaymentApproval(order, event);
		order.markPaid(event.approvedAt().toLocalDateTime());

		orderPaymentRepository.save(OrderPayment.create(
			order.getId(),
			event.paymentId(),
			event.userId(),
			event.amount(),
			event.approvedAt().toLocalDateTime()
		));
		outboxEventAppender.appendOrderPaid(order, event);
		removeExpirationQuietly(order.getId());
	}


	public void handlePaymentRefunded(PaymentRefundedEvent event) {
		Order order = findOrder(event.orderId());

		if (order.getOrderStatus() == OrderStatus.REFUNDED) {
			return;
		}

		validateOrderStatus(order, OrderStatus.PAID);
		order.refund(event.refundedAt().toLocalDateTime());
		outboxEventAppender.appendOrderRefund(order, event);
	}

	private Order findOrder(UUID orderId) {
		return orderRepository.findByIdWithOrderProducts(orderId)
			.orElseThrow(() -> new OrderException(ErrorCode.ORDER_NOT_FOUND));
	}

	private void validatePendingForPaymentApproval(Order order) {
		if (order.getOrderStatus() != OrderStatus.PENDING) {
			throw new OrderException(ErrorCode.ORDER_ALREADY_PROCESSED);
		}
	}

	private void validateOrderStatus(Order order, OrderStatus expectedStatus) {
		if (order.getOrderStatus() != expectedStatus) {
			throw new OrderException(ErrorCode.INVALID_ORDER_STATUS_TRANSITION);
		}
	}

	private void removeExpirationQuietly(UUID orderId) {
		try {
			orderExpirationStore.removeExpiration(orderId);
			orderExpirationStore.clearRetryCount(orderId);
		} catch (Exception exception) {
			log.warn("결제 완료 주문의 Redis 만료 대상 제거에 실패했습니다. orderId={}", orderId, exception);
		}
	}

}
