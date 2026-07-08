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

	private static final String TOPIC_PAYMENT_EVENTS = "payment.events";
	private static final String GROUP_ID = "order-service";

	private final ObjectMapper objectMapper;
	private final PaymentEventHandler paymentEventHandler;

	@KafkaListener(
		topics = TOPIC_PAYMENT_EVENTS,
		groupId = GROUP_ID,
		containerFactory = "paymentEventKafkaListenerContainerFactory"
	)
	public void consume(String message, Acknowledgment acknowledgment) {
		try {
			JsonNode root = readTree(message);
			String eventTypeStr = root.path("eventType").stringValue(null);

			if (eventTypeStr == null) {
				log.warn("필수 필드(eventType)가 누락된 메시지입니다. 무시합니다.");
				acknowledgment.acknowledge();
				return;
			}

			PaymentEventType eventType = PaymentEventType.from(eventTypeStr);
			if (shouldIgnore(eventType)) {
				log.warn("지원하지 않거나 처리하지 않는 결제 이벤트 타입입니다. eventType={}", eventTypeStr);
				acknowledgment.acknowledge();
				return;
			}

			JsonNode payload = root.path("payload");
			if (payload.isMissingNode() || payload.isNull()) {
				throw new OrderException(ErrorCode.INTERNAL_SERVER_ERROR, "결제 이벤트 payload가 누락되었습니다.");
			}

			paymentEventHandler.handle(eventType, eventTypeStr, payload);
			acknowledgment.acknowledge();
		} catch (Exception e) {
			log.error("메시지 처리 중 에러 발생: {}", e.getMessage(), e);

			throw e;
		}
	}

	private boolean shouldIgnore(PaymentEventType eventType) {
		return eventType == PaymentEventType.UNKNOWN
			|| eventType == PaymentEventType.PAYMENT_FAILED
			|| eventType == PaymentEventType.PAYMENT_CANCELED;
	}

	private JsonNode readTree(String message) {
		try {
			return objectMapper.readTree(message);
		} catch (JacksonException exception) {
			throw new OrderException(ErrorCode.INTERNAL_SERVER_ERROR, "결제 이벤트 메시지 파싱에 실패했습니다.");
		}
	}
}
