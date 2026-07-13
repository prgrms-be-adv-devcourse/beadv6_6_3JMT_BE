package com.prompthub.order.application.service.refund;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.application.service.event.order.OrderEventMessageFactory;
import com.prompthub.order.application.service.event.outbox.OutboxEventAppender;
import com.prompthub.order.application.service.order.OrderPolicyService;
import com.prompthub.order.application.usecase.OrderRefundUseCase;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderPayment;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.model.OrderRefund;
import com.prompthub.order.domain.repository.OrderPaymentRepository;
import com.prompthub.order.domain.repository.OrderRefundRepository;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.infra.messaging.kafka.event.RefundRequestedPayload;
import com.prompthub.order.presentation.dto.request.CreateOrderRefundRequest;
import com.prompthub.order.presentation.dto.response.OrderRefundResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
public class OrderRefundService implements OrderRefundUseCase {

	private final OrderRepository orderRepository;
	private final OrderPaymentRepository orderPaymentRepository;
	private final OrderRefundRepository orderRefundRepository;
	private final OrderEventMessageFactory eventMessageFactory;
	private final OutboxEventAppender outboxEventAppender;
	private final OrderPolicyService orderPolicyService;
	private final Clock clock;

	@Override
	public OrderRefundResponse requestRefund(UUID buyerId, UUID orderId, CreateOrderRefundRequest request) {
		orderPolicyService.validateUniqueProductIds(request.orderProductIds());
		Order order = orderRepository.findByIdWithOrderProductsForUpdate(orderId)
			.orElseThrow(() -> new OrderException(ErrorCode.ORDER_NOT_FOUND));
		if (!order.getBuyerId().equals(buyerId)) {
			throw new OrderException(ErrorCode.ORDER_ACCESS_DENIED);
		}

		List<OrderProduct> products = request.orderProductIds().stream()
			.map(id -> findProduct(order, id))
			.toList();
		if (products.stream().anyMatch(product -> !product.isRefundable())) {
			throw new OrderException(ErrorCode.ORDER_REFUND_NOT_ALLOWED);
		}

		OrderPayment payment = orderPaymentRepository.findByOrderId(orderId)
			.orElseThrow(() -> new OrderException(ErrorCode.ORDER_PAYMENT_NOT_FOUND));
		LocalDateTime requestedAt = LocalDateTime.now(clock);
		OrderRefund refund = OrderRefund.create(
			UUID.randomUUID(), order, payment.getPaymentId(), buyerId, request.reason(), requestedAt
		);
		products.forEach(product -> {
			product.requestRefund();
			refund.addProduct(product);
		});
		OrderRefund saved = orderRefundRepository.save(refund);

		RefundRequestedPayload payload = RefundRequestedPayload.from(saved);
		EventMessage<RefundRequestedPayload> message = eventMessageFactory
			.createRefundRequestedMessage(saved.getId(), payload);
		outboxEventAppender.append(message);
		return OrderRefundResponse.from(saved);
	}

	private OrderProduct findProduct(Order order, UUID orderProductId) {
		return order.getOrderProducts().stream()
			.filter(product -> product.getId().equals(orderProductId))
			.findFirst()
			.orElseThrow(() -> new OrderException(ErrorCode.ORDER_PRODUCT_NOT_FOUND));
	}
}
