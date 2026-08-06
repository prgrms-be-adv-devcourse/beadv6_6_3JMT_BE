package com.prompthub.order.infra.messaging.kafka.consumer;

import com.prompthub.order.application.event.product.ProductDeletedEvent;
import com.prompthub.order.application.event.product.ProductPriceChangedEvent;
import com.prompthub.order.application.event.product.ProductStoppedEvent;
import com.prompthub.order.application.service.event.OrderProductEventService;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.infra.messaging.kafka.consumer.product.ProductEventConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

class ProductEventConsumerTest {

	private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
	private static final LocalDateTime EVENT_TIME = LocalDateTime.of(2026, 6, 24, 10, 0);

	private OrderProductEventService orderProductEventService;
	private Acknowledgment acknowledgment;
	private ProductEventConsumer consumer;

	@BeforeEach
	void setUp() {
		orderProductEventService = mock(OrderProductEventService.class);
		acknowledgment = mock(Acknowledgment.class);
		consumer = new ProductEventConsumer(new ObjectMapper(), orderProductEventService);
	}

	@Test
	@DisplayName("PRODUCT_STOPPED 이벤트는 정지 처리로 위임하고 ack 한다")
	void consume_productStopped_delegatesAndAcknowledges() {
		String message = """
			{
			  "eventId": "%s",
			  "eventType": "PRODUCT_STOPPED",
			  "occurredAt": "%s",
			  "aggregateType": "PRODUCT",
			  "aggregateId": "%s",
			  "payload": {
			    "productId": "%s"
			  }
			}
			""".formatted(UUID.randomUUID(), EVENT_TIME, PRODUCT_ID, PRODUCT_ID);

		consumer.consume(message, acknowledgment);

		ArgumentCaptor<ProductStoppedEvent> captor = ArgumentCaptor.forClass(ProductStoppedEvent.class);
		then(orderProductEventService).should().handleProductStopped(captor.capture());
		assertThat(captor.getValue().eventType()).isEqualTo("PRODUCT_STOPPED");
		assertThat(captor.getValue().productId()).isEqualTo(PRODUCT_ID);
		assertThat(captor.getValue().occurredAt()).isEqualTo(EVENT_TIME);
		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("PRODUCT_DELETED 이벤트는 삭제 처리로 위임하고 ack 한다")
	void consume_productDeleted_delegatesAndAcknowledges() {
		String message = """
			{
			  "eventId": "%s",
			  "eventType": "PRODUCT_DELETED",
			  "occurredAt": "%s",
			  "aggregateType": "PRODUCT",
			  "aggregateId": "%s",
			  "payload": {
			    "productId": "%s"
			  }
			}
			""".formatted(UUID.randomUUID(), EVENT_TIME, PRODUCT_ID, PRODUCT_ID);

		consumer.consume(message, acknowledgment);

		ArgumentCaptor<ProductDeletedEvent> captor = ArgumentCaptor.forClass(ProductDeletedEvent.class);
		then(orderProductEventService).should().handleProductDeleted(captor.capture());
		assertThat(captor.getValue().eventType()).isEqualTo("PRODUCT_DELETED");
		assertThat(captor.getValue().productId()).isEqualTo(PRODUCT_ID);
		assertThat(captor.getValue().occurredAt()).isEqualTo(EVENT_TIME);
		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("PRODUCT_PRICE_CHANGED 이벤트는 가격 변경 처리로 위임하고 ack 한다")
	void consume_productPriceChanged_delegatesAndAcknowledges() {
		String message = """
			{
			  "eventId": "%s",
			  "eventType": "PRODUCT_PRICE_CHANGED",
			  "occurredAt": "%s",
			  "aggregateType": "PRODUCT",
			  "aggregateId": "%s",
			  "payload": {
			    "productId": "%s",
			    "previousPrice": 10000,
			    "changedPrice": 12000
			  }
			}
			""".formatted(UUID.randomUUID(), EVENT_TIME, PRODUCT_ID, PRODUCT_ID);

		consumer.consume(message, acknowledgment);

		ArgumentCaptor<ProductPriceChangedEvent> captor = ArgumentCaptor.forClass(ProductPriceChangedEvent.class);
		then(orderProductEventService).should().handleProductPriceChanged(captor.capture());
		assertThat(captor.getValue().eventType()).isEqualTo("PRODUCT_PRICE_CHANGED");
		assertThat(captor.getValue().productId()).isEqualTo(PRODUCT_ID);
		assertThat(captor.getValue().previousPrice()).isEqualTo(10000);
		assertThat(captor.getValue().changedPrice()).isEqualTo(12000);
		assertThat(captor.getValue().occurredAt()).isEqualTo(EVENT_TIME);
		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("지원하지 않는 eventType은 서비스 호출 없이 ack 한다")
	void consume_unknownEventType_ignoresAndAcknowledges() {
		String message = """
			{
			  "eventId": "%s",
			  "eventType": "PRODUCT_UNKNOWN",
			  "occurredAt": "%s",
			  "aggregateType": "PRODUCT",
			  "aggregateId": "%s",
			  "payload": {}
			}
			""".formatted(UUID.randomUUID(), EVENT_TIME, PRODUCT_ID);

		consumer.consume(message, acknowledgment);

		then(orderProductEventService).should(never()).handleProductStopped(any(ProductStoppedEvent.class));
		then(orderProductEventService).should(never()).handleProductDeleted(any(ProductDeletedEvent.class));
		then(orderProductEventService).should(never()).handleProductPriceChanged(any(ProductPriceChangedEvent.class));
		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("JSON 파싱 실패는 ack 하지 않고 예외를 전파한다")
	void consume_invalidJson_throwsWithoutAcknowledging() {
		String message = "{";

		assertThatThrownBy(() -> consumer.consume(message, acknowledgment))
			.isInstanceOf(OrderException.class);

		then(acknowledgment).should(never()).acknowledge();
	}
}
