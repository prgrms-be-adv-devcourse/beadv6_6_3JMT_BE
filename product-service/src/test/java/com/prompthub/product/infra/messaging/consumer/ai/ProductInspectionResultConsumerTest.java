package com.prompthub.product.infra.messaging.consumer.ai;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.prompthub.product.application.service.inspection.ProductInspectionResultHandler;
import com.prompthub.product.domain.model.vo.InspectionChecklist;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class ProductInspectionResultConsumerTest {

	private static final UUID EVENT_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
	private static final UUID PRODUCT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	@Mock
	private ProductInspectionResultHandler handler;

	@Mock
	private Acknowledgment acknowledgment;

	private ProductInspectionResultConsumer consumer;

	@BeforeEach
	void setUp() {
		consumer = new ProductInspectionResultConsumer(new ObjectMapper(), handler);
	}

	@Test
	@DisplayName("PRODUCT_INSPECTION_COMPLETED(승인)를 수신하면 approved=true로 handler를 호출한다")
	void consume_approved_callsHandler() {
		String message = eventMessage(true, null);

		consumer.consume(message, acknowledgment);

		then(handler).should().apply(eq(PRODUCT_ID), eq(true), eq((String) null), any());
		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("PRODUCT_INSPECTION_COMPLETED(반려)를 수신하면 사유와 함께 handler를 호출한다")
	void consume_rejected_callsHandlerWithReason() {
		String message = eventMessage(false, "금지 콘텐츠");

		consumer.consume(message, acknowledgment);

		then(handler).should().apply(eq(PRODUCT_ID), eq(false), eq("금지 콘텐츠"), any());
		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("payload의 체크리스트 7개 필드를 파싱해 handler에 전달한다")
	void consume_parsesChecklistFromPayload() {
		String message = "{\"eventId\":\"" + EVENT_ID + "\",\"eventType\":\"PRODUCT_INSPECTION_COMPLETED\","
			+ "\"aggregateType\":\"PRODUCT\",\"payload\":{\"productId\":\"" + PRODUCT_ID + "\","
			+ "\"approved\":true,\"rejectionReason\":null,"
			+ "\"hasContext\":true,\"hasObjective\":false,\"hasNuance\":true,\"hasTone\":false,"
			+ "\"hasExamples\":true,\"hasExecution\":false,\"hasRoleAssignment\":true}}";
		InspectionChecklist expected = new InspectionChecklist(true, false, true, false, true, false, true);

		consumer.consume(message, acknowledgment);

		then(handler).should().apply(PRODUCT_ID, true, null, expected);
		then(acknowledgment).should().acknowledge();
	}

	@Nested
	@DisplayName("예외/비지원 케이스")
	class EdgeCases {

		@Test
		@DisplayName("지원하지 않는 eventType은 handler를 호출하지 않고 acknowledge한다(DLT 아님)")
		void consume_unsupportedEventType_acknowledge() {
			String message = """
				{"eventId":"99999999-9999-9999-9999-999999999999","eventType":"SOMETHING_ELSE",\
				"aggregateType":"PRODUCT","payload":{"productId":"11111111-1111-1111-1111-111111111111"}}
				""";

			consumer.consume(message, acknowledgment);

			then(handler).shouldHaveNoInteractions();
			then(acknowledgment).should().acknowledge();
		}

		@Test
		@DisplayName("eventId가 없으면 예외를 던져 DLT로 보낸다")
		void consume_missingEventId_throws() {
			String message = """
				{"eventType":"PRODUCT_INSPECTION_COMPLETED","payload":{"productId":"11111111-1111-1111-1111-111111111111","approved":true}}
				""";

			assertThatThrownBy(() -> consumer.consume(message, acknowledgment))
				.isInstanceOf(IllegalArgumentException.class);
			then(handler).should(never()).apply(any(), anyBoolean(), any(), any());
			then(acknowledgment).should(never()).acknowledge();
		}

		@Test
		@DisplayName("handler가 IllegalStateException(중복 처리됨)을 던지면 acknowledge하고 DLT로 보내지 않는다")
		void consume_alreadyProcessed_acknowledgesWithoutDlt() {
			org.mockito.BDDMockito.willThrow(new IllegalStateException("이미 처리됨"))
				.given(handler).apply(eq(PRODUCT_ID), eq(true), eq((String) null), any());
			String message = eventMessage(true, null);

			consumer.consume(message, acknowledgment);

			then(acknowledgment).should().acknowledge();
		}
	}

	private String eventMessage(boolean approved, String rejectionReason) {
		String reasonField = rejectionReason == null ? "null" : "\"" + rejectionReason + "\"";
		return "{\"eventId\":\"" + EVENT_ID + "\",\"eventType\":\"PRODUCT_INSPECTION_COMPLETED\","
			+ "\"aggregateType\":\"PRODUCT\",\"payload\":{\"productId\":\"" + PRODUCT_ID
			+ "\",\"approved\":" + approved + ",\"rejectionReason\":" + reasonField + "}}";
	}
}
