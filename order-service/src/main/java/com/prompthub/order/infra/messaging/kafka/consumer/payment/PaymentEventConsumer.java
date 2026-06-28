package com.prompthub.order.infra.messaging.kafka.consumer.payment;

import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
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

	private static final String TOPIC = "payment.approved";
	private static final String GROUP_ID = "order-service";

	private final ObjectMapper objectMapper;
	private final PaymentEventHandler paymentEventHandler;

	@KafkaListener(
		topics = TOPIC,
		groupId = GROUP_ID,
		containerFactory = "paymentEventKafkaListenerContainerFactory"
	)
	public void consume(String message, Acknowledgment acknowledgment) {
		try {
			JsonNode root = readTree(message);
			String eventTypeStr = root.path("eventType").stringValue(null);

			if (eventTypeStr == null) {
				log.warn("필수 필드(eventId, eventType)가 누락된 메시지입니다. 무시합니다.");
				acknowledgment.acknowledge();
				return;
			}

			PaymentEventType eventType = PaymentEventType.from(eventTypeStr);
			paymentEventHandler.handle(eventType, eventTypeStr, GROUP_ID, root);
			acknowledgment.acknowledge();
		} catch (Exception e) {
			log.error("메시지 처리 중 에러 발생: {}", e.getMessage(), e);

			throw e;
		}
	}

	private JsonNode readTree(String message) {
		try {
			return objectMapper.readTree(message);
		} catch (JacksonException exception) {
			throw new OrderException(ErrorCode.INTERNAL_SERVER_ERROR, "결제 이벤트 메시지 파싱에 실패했습니다.");
		}
	}
}
