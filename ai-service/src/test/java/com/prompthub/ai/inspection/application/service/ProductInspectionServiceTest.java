package com.prompthub.ai.inspection.application.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import com.prompthub.ai.global.exception.AiErrorCode;
import com.prompthub.ai.global.exception.AiException;
import com.prompthub.ai.inspection.application.dto.ProductInspectionRequest;
import com.prompthub.ai.inspection.application.port.ProductInspectionAiPort;
import com.prompthub.ai.inspection.domain.model.InspectionVerdict;
import com.prompthub.ai.inspection.infrastructure.messaging.kafka.producer.InspectionEventProducer;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductInspectionServiceTest {

	private static final UUID PRODUCT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	@Mock
	private ProductInspectionAiPort aiPort;

	@Mock
	private InspectionEventProducer inspectionEventProducer;

	@InjectMocks
	private ProductInspectionService productInspectionService;

	@Test
	@DisplayName("AI가 승인하면 approved=true, 사유 없이 이벤트를 발행한다")
	void inspect_approved_publishesApprovedEvent() {
		given(aiPort.inspect(any())).willReturn(
			new InspectionVerdict(true, null, false, false, false, false, false, false, false));

		productInspectionService.inspect(request());

		then(inspectionEventProducer).should().publish(PRODUCT_ID, true, null);
	}

	@Test
	@DisplayName("AI가 반려하면 approved=false, 사유와 함께 이벤트를 발행한다")
	void inspect_rejected_publishesRejectedEventWithReason() {
		given(aiPort.inspect(any())).willReturn(
			new InspectionVerdict(false, "금지 콘텐츠 포함", false, false, false, false, false, false, false));

		productInspectionService.inspect(request());

		then(inspectionEventProducer).should().publish(PRODUCT_ID, false, "금지 콘텐츠 포함");
	}

	@Test
	@DisplayName("AI 호출이 실패하면 예외를 그대로 전파하고 이벤트를 발행하지 않는다(재시도/DLT는 상위에서 처리)")
	void inspect_aiPortThrows_propagatesWithoutPublishing() {
		willThrow(new AiException(AiErrorCode.AI_PROVIDER_UNAVAILABLE)).given(aiPort).inspect(any());

		assertThatThrownBy(() -> productInspectionService.inspect(request()))
			.isInstanceOf(AiException.class);
		then(inspectionEventProducer).shouldHaveNoInteractions();
	}

	private ProductInspectionRequest request() {
		return new ProductInspectionRequest(
			PRODUCT_ID, "PROMPT", "제목", "설명", "content", List.of("tag1"),
			"https://s3/presigned-thumb", List.of("https://s3/presigned-1"));
	}
}
