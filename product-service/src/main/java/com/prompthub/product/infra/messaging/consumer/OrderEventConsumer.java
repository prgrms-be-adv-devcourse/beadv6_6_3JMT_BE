package com.prompthub.product.infra.messaging.consumer;

import com.prompthub.product.application.service.ProductSalesCountService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
public class OrderEventConsumer {

	private final ObjectMapper objectMapper;
	private final ProductSalesCountService productSalesCountService;

	@KafkaListener(
		topics = "order-events",
		groupId = "product-service",
		containerFactory = "orderEventContainerFactory"
	)
	public void consume(String message, Acknowledgment acknowledgment) {
		try {
			JsonNode root = readTree(message);
			String eventType = root.path("eventType").stringValue(null);

			if (eventType == null) {
				log.warn("eventType 누락 메시지 무시");
				acknowledgment.acknowledge();
				return;
			}

			List<UUID> productIds = extractProductIds(root.path("payload").path("products"));

			switch (eventType) {
				case "ORDER_PAID" -> {
					productSalesCountService.incrementSalesCount(productIds);
					log.info("ORDER_PAID 처리 완료. productIds={}", productIds);
				}
				case "ORDER_REFUND" -> {
					productSalesCountService.decrementSalesCount(productIds);
					log.info("ORDER_REFUND 처리 완료. productIds={}", productIds);
				}
				default -> log.warn("처리하지 않는 주문 이벤트 타입: {}", eventType);
			}
			acknowledgment.acknowledge();
		} catch (Exception e) {
			log.error("주문 이벤트 처리 실패: {}", e.getMessage(), e);
			throw e;
		}
	}

	private JsonNode readTree(String message) {
		try {
			return objectMapper.readTree(message);
		} catch (JacksonException e) {
			throw new IllegalArgumentException("주문 이벤트 메시지 파싱 실패", e);
		}
	}

	private List<UUID> extractProductIds(JsonNode products) {
		List<UUID> productIds = new ArrayList<>();
		for (JsonNode product : products) {
			String productId = product.path("productId").stringValue(null);
			if (productId != null) {
				productIds.add(UUID.fromString(productId));
			}
		}
		return productIds;
	}
}
