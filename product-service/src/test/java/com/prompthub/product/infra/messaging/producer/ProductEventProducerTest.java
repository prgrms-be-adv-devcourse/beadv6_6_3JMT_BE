package com.prompthub.product.infra.messaging.producer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.prompthub.common.event.EventMessage;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.infra.messaging.producer.event.ProductReviewRequestedPayload;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static com.prompthub.product.support.ProductContentFixtures.freePromptContent;
import static com.prompthub.product.support.ProductContentFixtures.promptContent;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
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
			assertThat(payload.free()).isFalse();
		}

		@Test
		@DisplayName("무료 상품(AmountType.FREE)이면 payload의 free가 true다")
		void publishReviewRequested_freeProduct_setsFreeTrue() {
			Product product = Product.create(PRODUCT_ID, UUID.randomUUID(), freePromptContent());

			productEventProducer.publishReviewRequested(
				product, null, "https://s3/presigned-thumb", List.of("https://s3/presigned-1"));

			EventMessage<?> message = captureMessage();
			ProductReviewRequestedPayload payload = (ProductReviewRequestedPayload) message.payload();
			assertThat(payload.free()).isTrue();
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

	// send()가 돌려주는 future의 완료를 관측하는지 검증한다(2026-08-05 로드맵 PR4) — broker 전송이
	// 비동기로 실패해도 아무도 안 보면 이벤트가 조용히 사라지는 문제의 최소 방어.
	@Nested
	@DisplayName("발행 완료 관측(success/failure 콜백)")
	class SendObservability {

		private ListAppender<ILoggingEvent> logAppender;

		@BeforeEach
		void attachLogAppender() {
			Logger logger = (Logger) LoggerFactory.getLogger(ProductEventProducer.class);
			logger.setLevel(Level.DEBUG);
			logAppender = new ListAppender<>();
			logAppender.start();
			logger.addAppender(logAppender);
		}

		@AfterEach
		void detachLogAppender() {
			((Logger) LoggerFactory.getLogger(ProductEventProducer.class)).detachAppender(logAppender);
		}

		@Test
		@DisplayName("send future가 성공하면 debug 로그에 eventId·eventType·aggregateId·topic을 남긴다")
		void sendSucceeds_logsDebugWithEventFields() {
			ProducerRecord<String, Object> record = new ProducerRecord<>(TOPIC, PRODUCT_ID.toString(), null);
			RecordMetadata recordMetadata = new RecordMetadata(new TopicPartition(TOPIC, 0), 0, 0, 0, 0, 0);
			given(kafkaTemplate.send(eq(TOPIC), eq(PRODUCT_ID.toString()), any()))
				.willReturn(CompletableFuture.completedFuture(new SendResult<>(record, recordMetadata)));

			productEventProducer.publishReviewRequested(product(), null, null, List.of());

			assertThat(logAppender.list).anySatisfy(event -> {
				assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
				assertThat(event.getFormattedMessage())
					.contains("PRODUCT_REVIEW_REQUESTED", PRODUCT_ID.toString(), TOPIC);
			});
		}

		@Test
		@DisplayName("send future가 실패하면 error 로그에 eventId·eventType·aggregateId·topic·예외를 남긴다")
		void sendFails_logsErrorWithEventFieldsAndException() {
			RuntimeException brokerFailure = new RuntimeException("broker down");
			given(kafkaTemplate.send(eq(TOPIC), eq(PRODUCT_ID.toString()), any()))
				.willReturn(CompletableFuture.failedFuture(brokerFailure));

			productEventProducer.publishReviewRequested(product(), null, null, List.of());

			assertThat(logAppender.list).anySatisfy(event -> {
				assertThat(event.getLevel()).isEqualTo(Level.ERROR);
				assertThat(event.getFormattedMessage())
					.contains("PRODUCT_REVIEW_REQUESTED", PRODUCT_ID.toString(), TOPIC);
				assertThat(event.getThrowableProxy().getMessage()).isEqualTo("broker down");
			});
		}

		@Test
		@DisplayName("send()가 null future를 돌려줘도 예외 없이 넘어간다")
		void sendReturnsNullFuture_doesNotThrow() {
			given(kafkaTemplate.send(eq(TOPIC), eq(PRODUCT_ID.toString()), any())).willReturn(null);

			assertThatCode(() -> productEventProducer.publishReviewRequested(product(), null, null, List.of()))
				.doesNotThrowAnyException();
		}

		private Product product() {
			return Product.create(PRODUCT_ID, UUID.randomUUID(), promptContent());
		}
	}
}
