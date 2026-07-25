package com.prompthub.search.infra.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prompthub.search.application.ProductSearchEventHandler;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

@ExtendWith(MockitoExtension.class)
class ProductSearchEventConsumerTest {

	@Mock
	private ProductSearchEventHandler productSearchEventHandler;

	@Mock
	private Acknowledgment acknowledgment;

	private ProductSearchEventConsumer consumer;

	@BeforeEach
	void setUp() {
		ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
		consumer = new ProductSearchEventConsumer(objectMapper, productSearchEventHandler);
	}

	@Test
	void consume_PRODUCT_STOPPED는_productId를_구체_타입으로_매핑해서_전달한다() {
		UUID eventId = UUID.randomUUID();
		UUID productId = UUID.randomUUID();

		consumer.consume(message(eventId, "PRODUCT_STOPPED", "{\"productId\":\"" + productId + "\"}"), acknowledgment);

		verify(productSearchEventHandler).handleProductRemovalCandidate(
			eq(eventId), any(LocalDateTime.class), eq("PRODUCT_STOPPED"), eq(productId));
		verify(acknowledgment).acknowledge();
	}

	@Test
	void consume_PRODUCT_DELETED는_productId를_구체_타입으로_매핑해서_전달한다() {
		UUID eventId = UUID.randomUUID();
		UUID productId = UUID.randomUUID();

		consumer.consume(message(eventId, "PRODUCT_DELETED", "{\"productId\":\"" + productId + "\"}"), acknowledgment);

		verify(productSearchEventHandler).handleProductRemovalCandidate(
			eq(eventId), any(LocalDateTime.class), eq("PRODUCT_DELETED"), eq(productId));
		verify(acknowledgment).acknowledge();
	}

	@Test
	void consume_PRODUCT_STOPPED_payload에_productId가_없으면_예외를_던지고_ack하지_않는다() {
		String message = message(UUID.randomUUID(), "PRODUCT_STOPPED", "{}");

		assertThatThrownBy(() -> consumer.consume(message, acknowledgment))
			.isInstanceOf(NullPointerException.class);

		verify(acknowledgment, never()).acknowledge();
	}

	@Test
	void consume_PRODUCT_DELETED_payload에_productId가_없으면_예외를_던지고_ack하지_않는다() {
		String message = message(UUID.randomUUID(), "PRODUCT_DELETED", "{}");

		assertThatThrownBy(() -> consumer.consume(message, acknowledgment))
			.isInstanceOf(NullPointerException.class);

		verify(acknowledgment, never()).acknowledge();
	}

	private String message(UUID eventId, String eventType, String payloadJson) {
		return """
			{"eventId":"%s","eventType":"%s","occurredAt":"2026-07-24T00:00:00","aggregateType":"PRODUCT","aggregateId":"%s","payload":%s}
			""".formatted(eventId, eventType, UUID.randomUUID(), payloadJson);
	}
}
