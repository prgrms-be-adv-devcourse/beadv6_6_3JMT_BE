package com.prompthub.order.application.service.refund;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.application.event.refund.OrderRefundRequestedPayload;
import com.prompthub.order.application.service.event.OrderEventMessageFactory;
import com.prompthub.order.application.service.event.outbox.OutboxEventAppender;
import com.prompthub.order.application.usecase.OrderRefundUseCase;
import com.prompthub.order.domain.enums.OrderRefundStatus;
import com.prompthub.order.domain.enums.OrderStatus;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderPayment;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.model.OrderRefund;
import com.prompthub.order.domain.repository.OrderPaymentRepository;
import com.prompthub.order.domain.repository.OrderRefundRepository;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class OrderRefundService implements OrderRefundUseCase {

	private final OrderRepository orderRepository;
	private final OrderPaymentRepository orderPaymentRepository;
	private final OrderRefundRepository orderRefundRepository;
	private final OrderEventMessageFactory orderEventMessageFactory;
	private final OutboxEventAppender outboxEventAppender;
	private final OrderRefundReconciliationPolicy reconciliationPolicy;
	private final Clock clock;

	@Override
	public void requestRefund(
		UUID buyerId,
		UUID orderId,
		UUID paymentId,
		UUID orderProductId
	) {
		Order order = orderRepository.findByIdWithOrderProducts(orderId)
			.orElseThrow(() -> new OrderException(ErrorCode.ORDER_NOT_FOUND));
		validateOrderOwner(order, buyerId);

		OrderPayment payment = orderPaymentRepository.findByPaymentId(paymentId)
			.orElseThrow(() -> new OrderException(ErrorCode.ORDER_PAYMENT_NOT_FOUND));
		validatePayment(payment, order, buyerId);

		OrderProduct orderProduct = findOrderProduct(order, orderProductId);
		validateNoRefundInProgress(order);
		validateRefundable(order, orderProduct);
		validateNoExistingProductRefund(paymentId, orderProductId);

		LocalDateTime requestedAt = LocalDateTime.now(clock);
		int refundAmount = orderProduct.getProductAmount();
		OrderRefund orderRefund = OrderRefund.request(
			orderId,
			paymentId,
			buyerId,
			orderProductId,
			refundAmount,
			requestedAt,
			requestedAt.plusMinutes(reconciliationPolicy.initialDelayMinutes())
		);

		orderRefundRepository.save(orderRefund);
		order.requestRefund(orderProductId);

		OrderRefundRequestedPayload payload = new OrderRefundRequestedPayload(
			orderId,
			orderProductId,
			buyerId,
			refundAmount,
			requestedAt
		);
		EventMessage<OrderRefundRequestedPayload> message =
			orderEventMessageFactory.createOrderRefundRequestedMessage(orderId, payload);
		outboxEventAppender.append(message);

		log.info(
			"부분 환불 요청 접수 완료. orderId={}, orderProductId={}, refundRequestId={}",
			orderId,
			orderProductId,
			orderRefund.getId()
		);
	}

	private void validateOrderOwner(Order order, UUID buyerId) {
		if (!order.getBuyerId().equals(buyerId)) {
			throw new OrderException(ErrorCode.ORDER_ACCESS_DENIED);
		}
	}

	private void validatePayment(OrderPayment payment, Order order, UUID buyerId) {
		if (!payment.getOrderId().equals(order.getId())
			|| !payment.getBuyerId().equals(buyerId)) {
			throw new OrderException(ErrorCode.ORDER_PAYMENT_NOT_FOUND);
		}

		if (payment.getApprovedAmount() != order.getTotalOrderAmount()) {
			throw new OrderException(ErrorCode.ORDER_PAYMENT_AMOUNT_MISMATCH);
		}
	}

	private OrderProduct findOrderProduct(Order order, UUID orderProductId) {
		return order.getOrderProducts().stream()
			.filter(product -> product.getId().equals(orderProductId))
			.findFirst()
			.orElseThrow(() -> new OrderException(ErrorCode.ORDER_PRODUCT_NOT_FOUND));
	}

	private void validateRefundable(Order order, OrderProduct orderProduct) {
		boolean refundableOrder = order.getOrderStatus() == OrderStatus.PAID
			|| order.getOrderStatus() == OrderStatus.PARTIAL_REFUNDED;

		if (!refundableOrder || !orderProduct.isRefundable()) {
			throw new OrderException(ErrorCode.ORDER_REFUND_NOT_ALLOWED);
		}
	}

	private void validateNoRefundInProgress(Order order) {
		if (orderRefundRepository.existsByOrderIdAndStatus(
			order.getId(),
			OrderRefundStatus.REQUESTED
		)) {
			throw new OrderException(ErrorCode.ORDER_REFUND_IN_PROGRESS);
		}
	}

	private void validateNoExistingProductRefund(UUID paymentId, UUID orderProductId) {
		if (orderRefundRepository.findByPaymentIdAndOrderProductId(paymentId, orderProductId).isPresent()) {
			throw new OrderException(ErrorCode.ORDER_REFUND_NOT_ALLOWED);
		}
	}
}
