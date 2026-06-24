package com.prompthub.order.application.service.outbox;

import com.prompthub.order.application.event.OrderEventEnvelope;
import com.prompthub.order.application.event.OrderPaidEvent;
import com.prompthub.order.application.event.OrderPaidProduct;
import com.prompthub.order.application.event.PaymentApprovedEvent;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.model.OutboxEvent;
import com.prompthub.order.domain.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class OutboxEventAppender {

	private static final String ORDER_PAID = "ORDER_PAID";
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

	private String serialize(OrderEventEnvelope<OrderPaidEvent> payload) {
		try {
			return objectMapper.writeValueAsString(payload);
		} catch (JacksonException exception) {
			throw new IllegalStateException("Failed to serialize ORDER_PAID outbox payload.", exception);
		}
	}
}
