package com.prompthub.settlement.infrastructure.messaging.kafka.consumer.order;

import com.prompthub.settlement.application.event.OrderEventEnvelope;
import com.prompthub.settlement.application.event.OrderPaidEvent;
import com.prompthub.settlement.application.usecase.SettlementSourceUseCase;
import com.prompthub.settlement.global.exception.SettlementErrorCode;
import com.prompthub.settlement.global.exception.SettlementException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventConsumer {

	private static final String TOPIC = "order-events";
	private static final String GROUP_ID = "settlement-service";

	private final ObjectMapper objectMapper;
	private final SettlementSourceUseCase settlementSourceUseCase;

	@KafkaListener(
		topics = TOPIC,
		groupId = GROUP_ID,
		containerFactory = "orderEventKafkaListenerContainerFactory",
		autoStartup = "${settlement.kafka.listener.order.enabled:false}"
	)
	public void consume(String message, Acknowledgment acknowledgment) {
		JsonNode root = readTree(message);
		String eventTypeStr = root.path("eventType").stringValue(null);
		OrderEventType eventType = OrderEventType.from(eventTypeStr);

		switch (eventType) {
			case ORDER_PAID -> settlementSourceUseCase.recordOrderPaid(toEnvelope(root, OrderPaidEvent.class));
			case UNKNOWN -> log.warn("지원하지 않는 주문 이벤트 타입입니다. eventType={}", eventTypeStr);
		}

		acknowledgment.acknowledge();
	}

	private JsonNode readTree(String message) {
		try {
			return objectMapper.readTree(message);
		} catch (JacksonException exception) {
			throw new SettlementException(
				SettlementErrorCode.SETTLEMENT_EVENT_DESERIALIZE_FAILED, "주문 이벤트 메시지 파싱에 실패했습니다.");
		}
	}

	private <T> OrderEventEnvelope<T> toEnvelope(JsonNode root, Class<T> payloadType) {
		try {
			JavaType type = objectMapper.getTypeFactory()
				.constructParametricType(OrderEventEnvelope.class, payloadType);
			return objectMapper.treeToValue(root, type);
		} catch (JacksonException exception) {
			throw new SettlementException(
				SettlementErrorCode.SETTLEMENT_EVENT_DESERIALIZE_FAILED, "주문 이벤트 페이로드 역직렬화에 실패했습니다.");
		}
	}
}
