package com.prompthub.order.application.service.refund;

import com.prompthub.common.event.EventMessage;
import com.prompthub.order.application.dto.RefundResult;
import com.prompthub.order.application.service.event.OrderEventMessageFactory;
import com.prompthub.order.application.service.event.OrderEventMessageFactory.RefundRequestedPayload;
import com.prompthub.order.application.service.event.outbox.OutboxEventAppender;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.repository.OrderRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class OrderRefundService {
	private static final String REQUESTED_STATUS = "REQUESTED";

	private final OrderRepository orderRepository;
	private final OrderEventMessageFactory orderEventMessageFactory;
	private final OutboxEventAppender outboxEventAppender;
	private final Clock clock;

	public RefundResult requestRefund(UUID buyerId, UUID orderId, List<UUID> orderProductIds) {
		validateRequest(orderProductIds);
		Order order = orderRepository.findByIdWithOrderProductsForUpdate(orderId)
			.orElseThrow(() -> new OrderException(ErrorCode.ORDER_NOT_FOUND));
		if (!order.getBuyerId().equals(buyerId)) {
			throw new OrderException(ErrorCode.ORDER_ACCESS_DENIED);
		}

		List<OrderProduct> selectedProducts = orderProductIds.stream()
			.map(id -> order.getOrderProducts().stream()
				.filter(product -> product.getId().equals(id))
				.findFirst()
				.orElseThrow(() -> new OrderException(ErrorCode.ORDER_PRODUCT_NOT_FOUND)))
			.toList();
		LocalDateTime requestedAt = LocalDateTime.now(clock);
		UUID refundRequestId = UUID.randomUUID();
		int refundAmount = selectedProducts.stream()
			.mapToInt(OrderProduct::getProductAmount)
			.sum();

		order.requestRefund(orderProductIds);
		RefundRequestedPayload payload = new RefundRequestedPayload(
			orderId,
			refundRequestId,
			refundAmount,
			requestedAt
		);
		EventMessage<RefundRequestedPayload> message =
			orderEventMessageFactory.createOrderRefundRequestedMessage(orderId, payload);
		outboxEventAppender.append(message);

		log.info(
			"부분 환불 요청 접수 완료. orderId={}, refundRequestId={}, productCount={}, refundAmount={}",
			orderId,
			refundRequestId,
			orderProductIds.size(),
			refundAmount
		);
		return new RefundResult(
			refundRequestId,
			orderId,
			List.copyOf(orderProductIds),
			refundAmount,
			REQUESTED_STATUS
		);
	}

	private void validateRequest(List<UUID> orderProductIds) {
		if (orderProductIds == null
			|| orderProductIds.isEmpty()
			|| orderProductIds.stream().anyMatch(java.util.Objects::isNull)
			|| new HashSet<>(orderProductIds).size() != orderProductIds.size()) {
			throw new OrderException(ErrorCode.INVALID_INPUT_VALUE);
		}
	}
}
