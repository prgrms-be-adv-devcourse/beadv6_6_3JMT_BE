package com.prompthub.order.application.service.event.outbox;

import com.prompthub.order.application.event.OrderEventEnvelope;
import com.prompthub.order.application.event.OrderPaidEvent;
import com.prompthub.order.application.event.OrderPaidProduct;
import com.prompthub.order.application.event.OrderRefundEvent;
import com.prompthub.order.application.event.OrderRefundProduct;
import com.prompthub.order.application.event.PaymentApprovedEvent;
import com.prompthub.order.application.event.PaymentRefundedEvent;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.model.OutboxEvent;
import com.prompthub.order.domain.repository.OutboxEventRepository;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class OutboxEventAppender {

	private static final String ORDER_PAID = "ORDER_PAID";
	private static final String ORDER_REFUND = "ORDER_REFUND";
	private static final int ORDER_EVENT_VERSION = 1;

	private final ObjectMapper objectMapper;
	private final OutboxEventRepository outboxEventRepository;

	public OutboxEvent appendOrderPaid(Order order, PaymentApprovedEvent event) {
		UUID eventId = UUID.randomUUID();
		OrderPaidEvent orderPaidEvent = new OrderPaidEvent(
			order.getId(),
			event.buyerId(),
			order.getTotalOrderAmount(),
			order.getTotalProductCount(),
			event.approvedAt(),
			order.getOrderProducts().stream()
				.map(this::toOrderPaidProduct)
				.toList()
		);
		String payload = serialize(new OrderEventEnvelope<>(
			eventId,
			ORDER_PAID,
			ORDER_EVENT_VERSION,
			event.approvedAt(),
			order.getId(),
			orderPaidEvent
		));

		return outboxEventRepository.save(OutboxEvent.orderPaid(
			eventId,
			order.getId(),
			payload,
			event.approvedAt()
		));
	}

	public OutboxEvent appendOrderRefund(Order order, PaymentRefundedEvent event) {
		UUID eventId = UUID.randomUUID();
		OrderRefundEvent orderRefundEvent = new OrderRefundEvent(
			order.getId(),
			event.paymentId(),
			event.buyerId(),
			event.refundedAmount(),
			order.getTotalProductCount(),
			event.refundedAt(),
			order.getOrderProducts().stream()
				.map(this::toOrderRefundProduct)
				.toList()
		);
		String payload = serialize(new OrderEventEnvelope<>(
			eventId,
			ORDER_REFUND,
			ORDER_EVENT_VERSION,
			event.refundedAt(),
			order.getId(),
			orderRefundEvent
		));

		return outboxEventRepository.save(OutboxEvent.orderRefund(
			eventId,
			order.getId(),
			payload,
			event.refundedAt()
		));
	}

	private OrderPaidProduct toOrderPaidProduct(OrderProduct orderProduct) {
		return new OrderPaidProduct(
			orderProduct.getId(),
			orderProduct.getProductId(),
			orderProduct.getSellerId(),
			orderProduct.getProductTitle(),
			orderProduct.getProductType(),
			orderProduct.getProductAmount()
		);
	}

	private OrderRefundProduct toOrderRefundProduct(OrderProduct orderProduct) {
		return new OrderRefundProduct(
			orderProduct.getId(),
			orderProduct.getProductId(),
			orderProduct.getSellerId(),
			orderProduct.getProductTitle(),
			orderProduct.getProductType(),
			orderProduct.getProductAmount()
		);
	}

	private String serialize(OrderEventEnvelope<?> payload) {
		try {
			return objectMapper.writeValueAsString(payload);
		} catch (JacksonException exception) {
			throw new OrderException(ErrorCode.INTERNAL_SERVER_ERROR, "주문 아웃박스 페이로드 직렬화에 실패했습니다.");
		}
	}
}
