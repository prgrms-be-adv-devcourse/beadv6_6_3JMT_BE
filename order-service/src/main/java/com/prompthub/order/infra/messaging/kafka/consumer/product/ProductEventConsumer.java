package com.prompthub.order.infra.messaging.kafka.consumer.product;

import com.prompthub.order.application.event.product.ProductDeletedEvent;
import com.prompthub.order.application.event.product.ProductPriceChangedEvent;
import com.prompthub.order.application.event.product.ProductStoppedEvent;
import com.prompthub.order.application.service.event.OrderProductEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.OrderException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProductEventConsumer {

	private static final String TOPIC = "product-events";
	private static final String GROUP_ID = "order-service";

	private final ObjectMapper objectMapper;
	private final OrderProductEventService orderProductEventService;

	@KafkaListener(
		topics = TOPIC,
		groupId = GROUP_ID,
		containerFactory = "productEventKafkaListenerContainerFactory"
	)
	public void consume(String message, Acknowledgment acknowledgment) {
		JsonNode root = readTree(message);
		String eventTypeStr = root.path("eventType").stringValue(null);
		ProductEventType eventType = ProductEventType.from(eventTypeStr);

		switch (eventType) {
			case PRODUCT_STOPPED -> orderProductEventService.handleProductStopped(toEvent(root, ProductStoppedEvent.class));
			case PRODUCT_DELETED -> orderProductEventService.handleProductDeleted(toEvent(root, ProductDeletedEvent.class));
			case PRODUCT_PRICE_CHANGED -> orderProductEventService.handleProductPriceChanged(toEvent(root, ProductPriceChangedEvent.class));
			case UNKNOWN -> log.warn("지원하지 않는 상품 이벤트 타입입니다. eventType={}", eventTypeStr);
		}

		acknowledgment.acknowledge();
	}

	private JsonNode readTree(String message) {
		try {
			return objectMapper.readTree(message);
		} catch (JacksonException exception) {
			throw new OrderException(ErrorCode.INTERNAL_SERVER_ERROR, "상품 이벤트 메시지 파싱에 실패했습니다.");
		}
	}

	private <T> T toEvent(JsonNode root, Class<T> eventType) {
		try {
			return objectMapper.treeToValue(root, eventType);
		} catch (JacksonException exception) {
			throw new OrderException(ErrorCode.INTERNAL_SERVER_ERROR, "상품 이벤트 페이로드 역직렬화에 실패했습니다.");
		}
	}
}
