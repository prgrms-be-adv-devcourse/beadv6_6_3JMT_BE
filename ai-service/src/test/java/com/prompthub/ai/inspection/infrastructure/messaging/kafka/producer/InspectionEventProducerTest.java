package com.prompthub.ai.inspection.infrastructure.messaging.kafka.producer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;

import com.prompthub.ai.inspection.infrastructure.messaging.kafka.producer.event.ProductInspectionCompletedPayload;
import com.prompthub.common.event.EventMessage;
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

@ExtendWith(MockitoExtension.class)
class InspectionEventProducerTest {

	private static final UUID PRODUCT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final String TOPIC = "ai-events";

	@Mock
	private KafkaTemplate<String, Object> kafkaTemplate;

	@InjectMocks
	private InspectionEventProducer inspectionEventProducer;

	private EventMessage<?> captureMessage() {
		ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
		then(kafkaTemplate).should().send(eq(TOPIC), eq(PRODUCT_ID.toString()), captor.capture());
		return (EventMessage<?>) captor.getValue();
	}

	@Nested
	@DisplayName("PRODUCT_INSPECTION_COMPLETED 이벤트 발행")
	class Publish {

		@Test
		@DisplayName("승인 결과를 EventMessage 봉투로 감싸 ai-events에 발행한다")
		void publish_approved_sendsEnvelope() {
			inspectionEventProducer.publish(
				PRODUCT_ID, true, null,
				true, true, false, false, true, false, true);

			EventMessage<?> message = captureMessage();
			assertThat(message.eventId()).isNotNull();
			assertThat(message.eventType()).isEqualTo("PRODUCT_INSPECTION_COMPLETED");
			assertThat(message.aggregateType()).isEqualTo("PRODUCT");
			assertThat(message.aggregateId()).isEqualTo(PRODUCT_ID);
			assertThat(message.payload()).isInstanceOf(ProductInspectionCompletedPayload.class);
			ProductInspectionCompletedPayload payload = (ProductInspectionCompletedPayload) message.payload();
			assertThat(payload.productId()).isEqualTo(PRODUCT_ID);
			assertThat(payload.approved()).isTrue();
			assertThat(payload.rejectionReason()).isNull();
			assertThat(payload.hasContext()).isTrue();
			assertThat(payload.hasObjective()).isTrue();
			assertThat(payload.hasNuance()).isFalse();
			assertThat(payload.hasTone()).isFalse();
			assertThat(payload.hasExamples()).isTrue();
			assertThat(payload.hasExecution()).isFalse();
			assertThat(payload.hasRoleAssignment()).isTrue();
		}

		@Test
		@DisplayName("반려 결과를 사유와 함께 발행한다")
		void publish_rejected_sendsEnvelopeWithReason() {
			inspectionEventProducer.publish(
				PRODUCT_ID, false, "금지 콘텐츠 포함",
				false, false, false, false, false, false, false);

			EventMessage<?> message = captureMessage();
			ProductInspectionCompletedPayload payload = (ProductInspectionCompletedPayload) message.payload();
			assertThat(payload.approved()).isFalse();
			assertThat(payload.rejectionReason()).isEqualTo("금지 콘텐츠 포함");
			assertThat(payload.hasContext()).isFalse();
		}
	}
}
