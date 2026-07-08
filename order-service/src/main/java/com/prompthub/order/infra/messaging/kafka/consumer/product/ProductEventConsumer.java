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
import tools.jackson.core.type.TypeReference;
import com.prompthub.common.event.EventMessage;

import java.time.LocalDateTime;
import java.util.UUID;

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
		try {
			EventMessage<JsonNode> eventMessage = parseMessage(message);
			String eventTypeStr = eventMessage.eventType();
			if (eventTypeStr == null) {
				log.warn("필수 필드(eventType)가 누락된 메시지입니다. 무시합니다.");
				acknowledgment.acknowledge();
				return;
			}

			ProductEventType eventType = ProductEventType.from(eventTypeStr);
			if (eventType == ProductEventType.UNKNOWN) {
				log.warn("지원하지 않는 상품 이벤트 타입입니다. eventType={}", eventTypeStr);
				acknowledgment.acknowledge();
				return;
			}

			JsonNode payloadNode = eventMessage.payload();
			if (payloadNode == null || payloadNode.isMissingNode() || payloadNode.isNull()) {
				throw new OrderException(ErrorCode.INTERNAL_SERVER_ERROR, "상품 이벤트 payload가 누락되었습니다.");
			}

			LocalDateTime occurredAt = eventMessage.occurredAt();
			if (occurredAt == null) {
				throw new OrderException(ErrorCode.INTERNAL_SERVER_ERROR, "상품 이벤트 occurredAt이 누락되었습니다.");
			}


			switch (eventType) {
				case PRODUCT_STOPPED -> {
					UUID productId = UUID.fromString(payloadNode.path("productId").stringValue());
					orderProductEventService.handleProductStopped(new ProductStoppedEvent("PRODUCT_STOPPED", productId, occurredAt));
				}
				case PRODUCT_DELETED -> {
					UUID productId = UUID.fromString(payloadNode.path("productId").stringValue());
					orderProductEventService.handleProductDeleted(new ProductDeletedEvent("PRODUCT_DELETED", productId, occurredAt));
				}
				case PRODUCT_PRICE_CHANGED -> {
					UUID productId = UUID.fromString(payloadNode.path("productId").stringValue());
					int previousPrice = payloadNode.path("previousPrice").intValue();
					int changedPrice = payloadNode.path("changedPrice").intValue();
					orderProductEventService.handleProductPriceChanged(new ProductPriceChangedEvent("PRODUCT_PRICE_CHANGED", productId, previousPrice, changedPrice, occurredAt));
				}
			}

			acknowledgment.acknowledge();
		} catch (Exception e) {
			log.error("상품 메시지 처리 중 에러 발생: {}", e.getMessage(), e);
			throw e;
		}
	}

	private EventMessage<JsonNode> parseMessage(String message) {
		try {
			return objectMapper.readValue(message, new TypeReference<EventMessage<JsonNode>>() {});
		} catch (JacksonException exception) {
			throw new OrderException(ErrorCode.INTERNAL_SERVER_ERROR, "상품 이벤트 메시지 파싱에 실패했습니다.");
		}
	}
}
