package com.prompthub.ai.inspection.infrastructure.messaging.kafka.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.prompthub.ai.inspection.application.dto.ProductInspectionRequest;
import com.prompthub.ai.inspection.application.port.ProductInspectionAiPort;
import com.prompthub.ai.inspection.application.service.ProductInspectionService;
import com.prompthub.ai.inspection.domain.model.InspectionVerdict;
import com.prompthub.ai.inspection.infrastructure.messaging.kafka.producer.InspectionEventProducer;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.ObjectMapper;

/**
 * Kafka에서 온 원문 JSON 문자열 -> {@link ProductReviewRequestedConsumer} -> 실제
 * {@link ProductInspectionService} -> {@link InspectionEventProducer#publish} 인자까지
 * 한 흐름으로 검증한다. aiPort/inspectionEventProducer만 모킹하고 나머지는 실제 인스턴스를 쓴다.
 */
@ExtendWith(MockitoExtension.class)
class ProductReviewRequestedConsumerIntegrationTest {

	private static final UUID EVENT_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
	private static final UUID PRODUCT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	@Mock
	private ProductInspectionAiPort aiPort;

	@Mock
	private InspectionEventProducer inspectionEventProducer;

	@Mock
	private Acknowledgment acknowledgment;

	private ProductReviewRequestedConsumer consumer;

	@BeforeEach
	void setUp() {
		ProductInspectionService productInspectionService =
			new ProductInspectionService(aiPort, inspectionEventProducer);
		consumer = new ProductReviewRequestedConsumer(new ObjectMapper(), productInspectionService);
	}

	@Test
	@DisplayName("duplicateOfProductId가 있는 실제 형태의 이벤트는 AI 호출 없이 자동 반려 이벤트를 발행한다")
	void consume_realisticEventWithDuplicate_rejectsWithoutAiAndPublishes() {
		UUID duplicateOfProductId = UUID.fromString("22222222-2222-2222-2222-222222222222");
		String message = realisticEventMessage(duplicateOfProductId);

		consumer.consume(message, acknowledgment);

		then(aiPort).shouldHaveNoInteractions();
		then(inspectionEventProducer).should().publish(
			PRODUCT_ID, false,
			"기존 상품과 동일한 본문입니다. (원본 상품 ID: " + duplicateOfProductId + ")",
			false, false, false, false, false, false, false);
		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("duplicateOfProductId가 없는 실제 형태의 이벤트는 AI 판정 결과를 그대로 발행한다")
	void consume_realisticEventWithoutDuplicate_publishesAiVerdict() {
		ArgumentCaptor<ProductInspectionRequest> requestCaptor =
			ArgumentCaptor.forClass(ProductInspectionRequest.class);
		given(aiPort.inspect(any())).willReturn(
			new InspectionVerdict(true, null, true, true, false, false, true, false, true));
		String message = realisticEventMessage(null);

		consumer.consume(message, acknowledgment);

		then(aiPort).should().inspect(requestCaptor.capture());
		ProductInspectionRequest capturedRequest = requestCaptor.getValue();
		assertThat(capturedRequest.productId()).isEqualTo(PRODUCT_ID);
		assertThat(capturedRequest.duplicateOfProductId()).isNull();
		then(inspectionEventProducer).should().publish(
			PRODUCT_ID, true, null, true, true, false, false, true, false, true);
		then(acknowledgment).should().acknowledge();
	}

	/** product-service ProductReviewRequestedPayload와 동일한 형태의 완전한 JSON. */
	private String realisticEventMessage(UUID duplicateOfProductId) {
		String duplicateField = duplicateOfProductId != null
			? "\"" + duplicateOfProductId + "\""
			: "null";
		return "{"
			+ "\"eventId\":\"" + EVENT_ID + "\","
			+ "\"eventType\":\"PRODUCT_REVIEW_REQUESTED\","
			+ "\"occurredAt\":\"2026-07-29T10:00:00\","
			+ "\"aggregateType\":\"PRODUCT\","
			+ "\"aggregateId\":\"" + PRODUCT_ID + "\","
			+ "\"payload\":{"
			+ "\"productId\":\"" + PRODUCT_ID + "\","
			+ "\"productType\":\"PROMPT\","
			+ "\"name\":\"면접 답변 프롬프트\","
			+ "\"description\":\"설명\","
			+ "\"content\":\"내용\","
			+ "\"tags\":[\"tag1\",\"tag2\"],"
			+ "\"thumbnailUrl\":\"https://s3/presigned-thumb\","
			+ "\"imageUrls\":[\"https://s3/presigned-1\"],"
			+ "\"duplicateOfProductId\":" + duplicateField
			+ "}}";
	}
}
