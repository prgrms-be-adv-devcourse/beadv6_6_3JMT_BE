package com.prompthub.product.infra.messaging.producer;

import com.prompthub.common.event.EventMessage;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.infra.messaging.producer.event.ProductChangedPayload;
import com.prompthub.product.infra.messaging.producer.event.ProductPriceChangedPayload;
import com.prompthub.product.infra.messaging.producer.event.ProductReviewRequestedPayload;
import com.prompthub.product.infra.messaging.producer.event.ProductStoppedPayload;
import java.util.List;
import java.util.UUID;

import static com.prompthub.product.support.ProductContentFixtures.promptContent;
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

	// 활성 트랜잭션이 없으므로 즉시 발행 경로가 실행된다. 발행된 EventMessage 봉투/payload 계약을 검증한다.
	private EventMessage<?> captureMessage() {
		ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
		then(kafkaTemplate).should().send(eq(TOPIC), eq(PRODUCT_ID.toString()), captor.capture());
		return (EventMessage<?>) captor.getValue();
	}

	@Nested
	@DisplayName("PRODUCT_STOPPED 이벤트 발행")
	class PublishStopped {

		@Test
		@DisplayName("EventMessage 봉투로 감싸 product-events 토픽에 productId 키로 발행한다")
		void publishStopped_sendsEnvelope() {
			productEventProducer.publishStopped(PRODUCT_ID);

			EventMessage<?> message = captureMessage();
			assertThat(message.eventId()).isNotNull();
			assertThat(message.eventType()).isEqualTo("PRODUCT_STOPPED");
			assertThat(message.occurredAt()).isNotNull();
			assertThat(message.aggregateType()).isEqualTo("PRODUCT");
			assertThat(message.aggregateId()).isEqualTo(PRODUCT_ID);
			assertThat(message.payload()).isInstanceOf(ProductStoppedPayload.class);
			assertThat(((ProductStoppedPayload) message.payload()).productId()).isEqualTo(PRODUCT_ID);
		}
	}

	@Nested
	@DisplayName("PRODUCT_DELETED 이벤트 발행")
	class PublishDeleted {

		@Test
		@DisplayName("EventMessage 봉투로 감싸 PRODUCT_DELETED 를 발행한다")
		void publishDeleted_sendsEnvelope() {
			productEventProducer.publishDeleted(PRODUCT_ID);

			EventMessage<?> message = captureMessage();
			assertThat(message.eventId()).isNotNull();
			assertThat(message.eventType()).isEqualTo("PRODUCT_DELETED");
			assertThat(message.aggregateType()).isEqualTo("PRODUCT");
			assertThat(message.aggregateId()).isEqualTo(PRODUCT_ID);
		}
	}

	@Nested
	@DisplayName("PRODUCT_PRICE_CHANGED 이벤트 발행")
	class PublishPriceChanged {

		@Test
		@DisplayName("가격 정보를 payload 에 담아 EventMessage 봉투로 발행한다")
		void publishPriceChanged_sendsEnvelope() {
			productEventProducer.publishPriceChanged(PRODUCT_ID, 10000, 8000);

			EventMessage<?> message = captureMessage();
			assertThat(message.eventType()).isEqualTo("PRODUCT_PRICE_CHANGED");
			assertThat(message.payload()).isInstanceOf(ProductPriceChangedPayload.class);
			ProductPriceChangedPayload payload = (ProductPriceChangedPayload) message.payload();
			assertThat(payload.productId()).isEqualTo(PRODUCT_ID);
			assertThat(payload.previousPrice()).isEqualTo(10000);
			assertThat(payload.changedPrice()).isEqualTo(8000);
		}
	}

	@Nested
	@DisplayName("PRODUCT_CHANGED 이벤트 발행")
	class PublishProductChanged {

		@Test
		@DisplayName("EventMessage 봉투로 감싸 familyRootId를 payload로 발행한다")
		void publishProductChanged_sendsEnvelope() {
			productEventProducer.publishProductChanged(PRODUCT_ID);

			EventMessage<?> message = captureMessage();
			assertThat(message.eventId()).isNotNull();
			assertThat(message.eventType()).isEqualTo("PRODUCT_CHANGED");
			assertThat(message.aggregateType()).isEqualTo("PRODUCT");
			assertThat(message.aggregateId()).isEqualTo(PRODUCT_ID);
			assertThat(message.payload()).isInstanceOf(ProductChangedPayload.class);
			assertThat(((ProductChangedPayload) message.payload()).familyRootId()).isEqualTo(PRODUCT_ID);
		}
	}

	@Nested
	@DisplayName("PRODUCT_REVIEW_REQUESTED 이벤트 발행")
	class PublishReviewRequested {

		@Test
		@DisplayName("상품 스냅샷과 presign된 이미지 URL을 payload로 담아 발행한다")
		void publishReviewRequested_sendsEnvelopeWithSnapshot() {
			Product product = Product.create(PRODUCT_ID, UUID.randomUUID(), promptContent());

			productEventProducer.publishReviewRequested(
				product, null, "https://s3/presigned-thumb", List.of("https://s3/presigned-1"));

			EventMessage<?> message = captureMessage();
			assertThat(message.eventType()).isEqualTo("PRODUCT_REVIEW_REQUESTED");
			assertThat(message.aggregateType()).isEqualTo("PRODUCT");
			assertThat(message.aggregateId()).isEqualTo(PRODUCT_ID);
			assertThat(message.payload()).isInstanceOf(ProductReviewRequestedPayload.class);
			ProductReviewRequestedPayload payload = (ProductReviewRequestedPayload) message.payload();
			assertThat(payload.productId()).isEqualTo(PRODUCT_ID);
			assertThat(payload.productType()).isEqualTo("PROMPT");
			assertThat(payload.name()).isEqualTo("제목");
			assertThat(payload.thumbnailUrl()).isEqualTo("https://s3/presigned-thumb");
			assertThat(payload.imageUrls()).containsExactly("https://s3/presigned-1");
			assertThat(payload.duplicateOfProductId()).isNull();
		}

		@Test
		@DisplayName("duplicateOfProductId가 있으면 payload에 그대로 담는다")
		void publishReviewRequested_withDuplicate_includesDuplicateOfProductId() {
			Product product = Product.create(PRODUCT_ID, UUID.randomUUID(), promptContent());
			UUID duplicateOfProductId = UUID.randomUUID();

			productEventProducer.publishReviewRequested(product, duplicateOfProductId, null, List.of());

			ProductReviewRequestedPayload payload =
				(ProductReviewRequestedPayload) captureMessage().payload();
			assertThat(payload.duplicateOfProductId()).isEqualTo(duplicateOfProductId);
		}
	}
}
