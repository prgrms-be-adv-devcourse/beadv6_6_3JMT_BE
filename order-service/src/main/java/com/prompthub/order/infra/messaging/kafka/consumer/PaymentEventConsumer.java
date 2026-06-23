package com.prompthub.order.infra.messaging.kafka.consumer;

import com.prompthub.order.application.event.PaymentApprovedEvent;
import com.prompthub.order.application.event.PaymentCanceledEvent;
import com.prompthub.order.application.event.PaymentFailedEvent;
import com.prompthub.order.application.event.PaymentRefundedEvent;
import com.prompthub.order.application.service.OrderPaymentEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventConsumer {

	private static final String TOPIC = "payment-events";
	private static final String GROUP_ID = "order-service";

	private final ObjectMapper objectMapper;
	private final OrderPaymentEventService orderPaymentEventService;

	@KafkaListener(
		topics = TOPIC,
		groupId = GROUP_ID,
		containerFactory = "paymentEventKafkaListenerContainerFactory"
	)
	public void consume(String message, Acknowledgment acknowledgment) {
		JsonNode root = readTree(message);
		String eventType = root.path("eventType").stringValue(null);

		switch (eventType) {
			case "PAYMENT_APPROVED" -> orderPaymentEventService.handlePaymentApproved(toEvent(root, PaymentApprovedEvent.class));
			case "PAYMENT_FAILED" -> orderPaymentEventService.handlePaymentFailed(toEvent(root, PaymentFailedEvent.class));
			case "PAYMENT_CANCELED" -> orderPaymentEventService.handlePaymentCanceled(toEvent(root, PaymentCanceledEvent.class));
			case "PAYMENT_REFUNDED" -> orderPaymentEventService.handlePaymentRefunded(toEvent(root, PaymentRefundedEvent.class));
			default -> log.warn("Unsupported payment eventType received. eventType={}", eventType);
		}

		acknowledgment.acknowledge();
	}

	private JsonNode readTree(String message) {
		try {
			return objectMapper.readTree(message);
		} catch (JacksonException exception) {
			throw new IllegalArgumentException("Failed to parse payment event message.", exception);
		}
	}

	private <T> T toEvent(JsonNode root, Class<T> eventType) {
		try {
			return objectMapper.treeToValue(root, eventType);
		} catch (JacksonException exception) {
			throw new IllegalArgumentException("Failed to deserialize payment event payload.", exception);
		}
	}
}
