package com.prompthub.order.application.service;

import com.prompthub.order.application.event.PaymentApprovedEvent;
import com.prompthub.order.application.event.PaymentCanceledEvent;
import com.prompthub.order.application.event.PaymentFailedEvent;
import com.prompthub.order.application.event.PaymentRefundedEvent;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderOutbox;
import com.prompthub.order.domain.model.OrderPayment;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.repository.CartRepository;
import com.prompthub.order.domain.repository.OrderOutboxRepository;
import com.prompthub.order.domain.repository.OrderPaymentRepository;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
@RequiredArgsConstructor
public class OrderPaymentEventService {

	private final OrderRepository orderRepository;
	private final CartRepository cartRepository;
	private final OrderPaymentRepository orderPaymentRepository;
	private final OrderOutboxRepository orderOutboxRepository;
	private final OrderPolicyService orderPolicyService;

	public void handlePaymentApproved(PaymentApprovedEvent event) {
		Order order = findOrder(event.orderId());
		boolean orderPaymentExists = orderPaymentRepository.existsByOrderId(event.orderId());

		if (order.getOrderStatus() == OrderStatus.PAID && orderPaymentExists) {
			return;
		}

		validatePendingForPaymentApproval(order);
		orderPolicyService.validatePaymentApproval(order, event);
		order.markPaid(event.approvedAt());

		orderPaymentRepository.save(OrderPayment.create(
			order.getId(),
			event.paymentId(),
			event.buyerId(),
			event.approvedAmount(),
			event.approvedAt()
		));
		orderOutboxRepository.save(OrderOutbox.orderPaid(
			order.getId(),
			createOrderPaidPayload(order, event),
			event.approvedAt()
		));

		var orderedProductIds = order.getOrderProducts().stream()
			.map(OrderProduct::getProductId)
			.toList();

		cartRepository.findByBuyerIdWithCartProducts(order.getBuyerId())
			.ifPresent(cart -> cart.removeProductsByProductIds(orderedProductIds));
	}

	public void handlePaymentFailed(PaymentFailedEvent event) {
		Order order = findOrder(event.orderId());

		if (order.getOrderStatus() == OrderStatus.FAILED) {
			return;
		}

		validateOrderStatus(order, OrderStatus.PENDING);
		order.markFailed();
	}

	public void handlePaymentCanceled(PaymentCanceledEvent event) {
		Order order = findOrder(event.orderId());

		if (order.getOrderStatus() == OrderStatus.CANCELED) {
			return;
		}

		validateOrderStatus(order, OrderStatus.PAID);
		order.cancel(event.canceledAt());
	}

	public void handlePaymentRefunded(PaymentRefundedEvent event) {
		Order order = findOrder(event.orderId());

		if (order.getOrderStatus() == OrderStatus.REFUNDED) {
			return;
		}

		validateOrderStatus(order, OrderStatus.PAID);
		order.refund(event.refundedAt());
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

	private String createOrderPaidPayload(Order order, PaymentApprovedEvent event) {
		String orderProductIds = order.getOrderProducts().stream()
			.map(OrderProduct::getId)
			.map(id -> "\"" + id + "\"")
			.collect(Collectors.joining(","));

		return """
			{"orderId":"%s","buyerId":"%s","paymentId":"%s","totalAmount":%d,"paidAt":"%s","orderProductIds":[%s]}"""
			.formatted(
				order.getId(),
				event.buyerId(),
				event.paymentId(),
				order.getTotalOrderAmount(),
				event.approvedAt(),
				orderProductIds
			);
	}
}
