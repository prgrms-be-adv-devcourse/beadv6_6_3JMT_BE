package com.prompthub.product.infra.messaging.producer;

import com.prompthub.product.infra.messaging.producer.event.ProductDeletedEvent;
import com.prompthub.product.infra.messaging.producer.event.ProductPriceChangedEvent;
import com.prompthub.product.infra.messaging.producer.event.ProductStoppedEvent;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class ProductEventProducerTest {

	private static final UUID PRODUCT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final String TOPIC = "product-events";

	@Mock
	private KafkaTemplate<String, Object> kafkaTemplate;

	@InjectMocks
	private ProductEventProducer productEventProducer;

	@Nested
	@DisplayName("PRODUCT_STOPPED 이벤트 발행")
	class PublishStopped {

		@Test
		@DisplayName("product-events 토픽에 productId를 키로 PRODUCT_STOPPED 이벤트를 발행한다")
		void publishStopped_sendsCorrectEvent() {
			ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);

			productEventProducer.publishStopped(PRODUCT_ID);

			then(kafkaTemplate).should().send(eq(TOPIC), eq(PRODUCT_ID.toString()), captor.capture());
			ProductStoppedEvent event = (ProductStoppedEvent) captor.getValue();
			assertThat(event.eventType()).isEqualTo("PRODUCT_STOPPED");
			assertThat(event.productId()).isEqualTo(PRODUCT_ID);
			assertThat(event.occurredAt()).isNotNull();
		}
	}

	@Nested
	@DisplayName("PRODUCT_DELETED 이벤트 발행")
	class PublishDeleted {

		@Test
		@DisplayName("product-events 토픽에 productId를 키로 PRODUCT_DELETED 이벤트를 발행한다")
		void publishDeleted_sendsCorrectEvent() {
			ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);

			productEventProducer.publishDeleted(PRODUCT_ID);

			then(kafkaTemplate).should().send(eq(TOPIC), eq(PRODUCT_ID.toString()), captor.capture());
			ProductDeletedEvent event = (ProductDeletedEvent) captor.getValue();
			assertThat(event.eventType()).isEqualTo("PRODUCT_DELETED");
			assertThat(event.productId()).isEqualTo(PRODUCT_ID);
			assertThat(event.occurredAt()).isNotNull();
		}
	}

	@Nested
	@DisplayName("PRODUCT_PRICE_CHANGED 이벤트 발행")
	class PublishPriceChanged {

		@Test
		@DisplayName("product-events 토픽에 가격 변경 정보를 포함한 PRODUCT_PRICE_CHANGED 이벤트를 발행한다")
		void publishPriceChanged_sendsCorrectEvent() {
			ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);

			productEventProducer.publishPriceChanged(PRODUCT_ID, 10000, 8000);

			then(kafkaTemplate).should().send(eq(TOPIC), eq(PRODUCT_ID.toString()), captor.capture());
			ProductPriceChangedEvent event = (ProductPriceChangedEvent) captor.getValue();
			assertThat(event.eventType()).isEqualTo("PRODUCT_PRICE_CHANGED");
			assertThat(event.productId()).isEqualTo(PRODUCT_ID);
			assertThat(event.previousPrice()).isEqualTo(10000);
			assertThat(event.changedPrice()).isEqualTo(8000);
			assertThat(event.occurredAt()).isNotNull();
		}
	}
}
