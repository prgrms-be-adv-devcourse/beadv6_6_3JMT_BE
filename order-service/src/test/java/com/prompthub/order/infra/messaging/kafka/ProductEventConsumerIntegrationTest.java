package com.prompthub.order.infra.messaging.kafka;

import com.prompthub.order.application.event.product.ProductDeletedEvent;
import com.prompthub.order.application.event.product.ProductPriceChangedEvent;
import com.prompthub.order.application.event.product.ProductStoppedEvent;
import com.prompthub.order.application.service.event.OrderProductEventService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ProductEventConsumerIntegrationTest extends KafkaIntegrationTest {

	@Autowired
	private KafkaTemplate<String, Object> kafkaTemplate;

	@MockitoBean
	private OrderProductEventService orderProductEventService;

	@Test
	@DisplayName("product-service가 발행한 PRODUCT_STOPPED 이벤트를 수신하면 eventType과 productId를 보존해 위임한다")
	void consumeProductStoppedEventFromKafka() {
		UUID productId = UUID.randomUUID();
		LocalDateTime occurredAt = LocalDateTime.of(2026, 6, 28, 15, 0);
		Map<String, Object> message = productEvent("PRODUCT_STOPPED", productId, occurredAt);

		kafkaTemplate.send("product-events", productId.toString(), message);

		await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
			verify(orderProductEventService).handleProductStopped(argThat(event ->
				isExpectedProductStoppedEvent(event, productId, occurredAt)
			))
		);
	}

	@Test
	@DisplayName("product-service가 발행한 PRODUCT_DELETED 이벤트를 수신하면 eventType과 productId를 보존해 위임한다")
	void consumeProductDeletedEventFromKafka() {
		UUID productId = UUID.randomUUID();
		LocalDateTime occurredAt = LocalDateTime.of(2026, 6, 28, 15, 5);
		Map<String, Object> message = productEvent("PRODUCT_DELETED", productId, occurredAt);

		kafkaTemplate.send("product-events", productId.toString(), message);

		await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
			verify(orderProductEventService).handleProductDeleted(argThat(event ->
				isExpectedProductDeletedEvent(event, productId, occurredAt)
			))
		);
	}

	@Test
	@DisplayName("product-service가 발행한 PRODUCT_PRICE_CHANGED 이벤트를 수신하면 eventType과 가격 정보를 보존해 위임한다")
	void consumeProductPriceChangedEventFromKafka() {
		UUID productId = UUID.randomUUID();
		LocalDateTime occurredAt = LocalDateTime.of(2026, 6, 28, 15, 0);
		Map<String, Object> message = productPriceChangedEvent(productId, occurredAt);

		kafkaTemplate.send("product-events", productId.toString(), message);

		await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
			verify(orderProductEventService).handleProductPriceChanged(argThat(event ->
				isExpectedProductPriceChangedEvent(event, productId, occurredAt)
			))
		);
	}

	@Test
	@DisplayName("동일한 PRODUCT_PRICE_CHANGED 이벤트를 중복 수신해도 예외 없이 각 메시지를 위임한다")
	void consumeDuplicateProductPriceChangedEventFromKafka() {
		UUID productId = UUID.randomUUID();
		LocalDateTime occurredAt = LocalDateTime.of(2026, 6, 28, 15, 10);
		Map<String, Object> message = productPriceChangedEvent(productId, occurredAt);

		kafkaTemplate.send("product-events", productId.toString(), message);
		kafkaTemplate.send("product-events", productId.toString(), message);

		await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
			verify(orderProductEventService, times(2)).handleProductPriceChanged(argThat(event ->
				isExpectedProductPriceChangedEvent(event, productId, occurredAt)
			))
		);
	}

	private Map<String, Object> productEvent(String eventType, UUID productId, LocalDateTime occurredAt) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("productId", productId.toString());

		Map<String, Object> message = new LinkedHashMap<>();
		message.put("eventId", UUID.randomUUID().toString());
		message.put("eventType", eventType);
		message.put("occurredAt", occurredAt.toString());
		message.put("aggregateType", "PRODUCT");
		message.put("aggregateId", productId.toString());
		message.put("payload", payload);
		return message;
	}

	private Map<String, Object> productPriceChangedEvent(UUID productId, LocalDateTime occurredAt) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("productId", productId.toString());
		payload.put("previousPrice", 10000);
		payload.put("changedPrice", 8000);

		Map<String, Object> message = new LinkedHashMap<>();
		message.put("eventId", UUID.randomUUID().toString());
		message.put("eventType", "PRODUCT_PRICE_CHANGED");
		message.put("occurredAt", occurredAt.toString());
		message.put("aggregateType", "PRODUCT");
		message.put("aggregateId", productId.toString());
		message.put("payload", payload);
		return message;
	}

	private boolean isExpectedProductStoppedEvent(
		ProductStoppedEvent event,
		UUID productId,
		LocalDateTime occurredAt
	) {
		return event != null
			&& "PRODUCT_STOPPED".equals(event.eventType())
			&& productId.equals(event.productId())
			&& occurredAt.equals(event.occurredAt());
	}

	private boolean isExpectedProductDeletedEvent(
		ProductDeletedEvent event,
		UUID productId,
		LocalDateTime occurredAt
	) {
		return event != null
			&& "PRODUCT_DELETED".equals(event.eventType())
			&& productId.equals(event.productId())
			&& occurredAt.equals(event.occurredAt());
	}

	private boolean isExpectedProductPriceChangedEvent(
		ProductPriceChangedEvent event,
		UUID productId,
		LocalDateTime occurredAt
	) {
		return event != null
			&& "PRODUCT_PRICE_CHANGED".equals(event.eventType())
			&& productId.equals(event.productId())
			&& event.previousPrice() == 10000
			&& event.changedPrice() == 8000
			&& occurredAt.equals(event.occurredAt());
	}
}
