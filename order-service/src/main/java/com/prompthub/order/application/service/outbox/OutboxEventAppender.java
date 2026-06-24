package com.prompthub.order.application.service.outbox;

import com.prompthub.order.application.event.PaymentApprovedEvent;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.domain.model.OrderProduct;
import com.prompthub.order.domain.model.OutboxEvent;
import com.prompthub.order.domain.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class OutboxEventAppender {

	private final ObjectMapper objectMapper;
	private final OutboxEventRepository outboxEventRepository;

	public OutboxEvent appendOrderPaid(Order order, PaymentApprovedEvent event) {
		String payload = serialize(new OrderPaidOutboxPayload(
			order.getId(),
			event.buyerId(),
			event.paymentId(),
			order.getTotalOrderAmount(),
			event.approvedAt(),
			order.getOrderProducts().stream()
				.map(OrderProduct::getId)
				.toList()
		));

		return outboxEventRepository.save(OutboxEvent.orderPaid(
			order.getId(),
			payload,
			event.approvedAt()
		));
	}

	private String serialize(OrderPaidOutboxPayload payload) {
		try {
			return objectMapper.writeValueAsString(payload);
		} catch (JacksonException exception) {
			throw new IllegalStateException("Failed to serialize ORDER_PAID outbox payload.", exception);
		}
	}
}
