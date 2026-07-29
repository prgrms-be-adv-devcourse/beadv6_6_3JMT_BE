package com.prompthub.ai.inspection.infrastructure.messaging.kafka.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.prompthub.ai.inspection.application.dto.ProductInspectionRequest;
import com.prompthub.ai.inspection.application.usecase.ProductInspectionUseCase;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class ProductReviewRequestedConsumerTest {

	private static final UUID EVENT_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
	private static final UUID PRODUCT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	@Mock
	private ProductInspectionUseCase productInspectionUseCase;

	@Mock
	private Acknowledgment acknowledgment;

	private ProductReviewRequestedConsumer consumer;

	@BeforeEach
	void setUp() {
		consumer = new ProductReviewRequestedConsumer(new ObjectMapper(), productInspectionUseCase);
	}

	@Test
	@DisplayName("PRODUCT_REVIEW_REQUESTED를 수신하면 payload를 파싱해 유스케이스를 호출한다")
	void consume_reviewRequested_callsUseCase() {
		String message = eventMessage();
		ArgumentCaptor<ProductInspectionRequest> captor = ArgumentCaptor.forClass(ProductInspectionRequest.class);

		consumer.consume(message, acknowledgment);

		then(productInspectionUseCase).should().inspect(captor.capture());
		ProductInspectionRequest request = captor.getValue();
		assertThat(request.productId()).isEqualTo(PRODUCT_ID);
		assertThat(request.productType()).isEqualTo("PROMPT");
		assertThat(request.name()).isEqualTo("상품명");
		assertThat(request.tags()).containsExactly("tag1", "tag2");
		assertThat(request.imageUrls()).containsExactly("https://s3/presigned-1");
		then(acknowledgment).should().acknowledge();
	}

	@Nested
	@DisplayName("예외/비지원 케이스")
	class EdgeCases {

		@Test
		@DisplayName("PRODUCT_CHANGED 등 다른 product-events 타입은 유스케이스를 호출하지 않고 acknowledge한다")
		void consume_unsupportedEventType_acknowledge() {
			String message = """
				{"eventId":"99999999-9999-9999-9999-999999999999","eventType":"PRODUCT_CHANGED",\
				"aggregateType":"PRODUCT","payload":{"familyRootId":"11111111-1111-1111-1111-111111111111"}}
				""";

			consumer.consume(message, acknowledgment);

			then(productInspectionUseCase).shouldHaveNoInteractions();
			then(acknowledgment).should().acknowledge();
		}

		@Test
		@DisplayName("eventId가 없으면 예외를 던져 DLT로 보낸다")
		void consume_missingEventId_throws() {
			String message = """
				{"eventType":"PRODUCT_REVIEW_REQUESTED","payload":{"productId":"11111111-1111-1111-1111-111111111111"}}
				""";

			assertThatThrownBy(() -> consumer.consume(message, acknowledgment))
				.isInstanceOf(IllegalArgumentException.class);
			then(productInspectionUseCase).should(never()).inspect(any());
			then(acknowledgment).should(never()).acknowledge();
		}
	}

	private String eventMessage() {
		return "{\"eventId\":\"" + EVENT_ID + "\",\"eventType\":\"PRODUCT_REVIEW_REQUESTED\","
			+ "\"aggregateType\":\"PRODUCT\",\"payload\":{"
			+ "\"productId\":\"" + PRODUCT_ID + "\","
			+ "\"productType\":\"PROMPT\",\"name\":\"상품명\",\"description\":\"설명\",\"content\":\"내용\","
			+ "\"tags\":[\"tag1\",\"tag2\"],"
			+ "\"thumbnailUrl\":\"https://s3/presigned-thumb\","
			+ "\"imageUrls\":[\"https://s3/presigned-1\"]}}";
	}
}
