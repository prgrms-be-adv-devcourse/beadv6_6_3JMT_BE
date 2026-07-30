package com.prompthub.ai.inspection.infrastructure.client.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.prompthub.ai.global.config.AiSettlementProperties;
import com.prompthub.ai.global.exception.AiException;
import com.prompthub.ai.inspection.application.dto.ProductInspectionRequest;
import com.prompthub.ai.inspection.domain.model.InspectionVerdict;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

class SpringAiProductInspectionAgentTest {

	private static final UUID PRODUCT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	@Test
	@DisplayName("이미지가 있으면 UserMessage에 media로 첨부해 호출한다")
	void inspect_withImages_attachesMedia() {
		ChatModel chatModel = mock(ChatModel.class);
		given(chatModel.call(any(Prompt.class))).willReturn(textResponse(
			"{\"approved\":true,\"rejectionReason\":null,"
				+ "\"hasContext\":true,\"hasObjective\":true,\"hasNuance\":false,"
				+ "\"hasTone\":false,\"hasExamples\":true,\"hasExecution\":false,\"hasRoleAssignment\":true}"));
		SpringAiProductInspectionAgent agent = new SpringAiProductInspectionAgent(
			chatModel, new InspectionPromptFactory(), properties());

		InspectionVerdict verdict = agent.inspect(request(List.of("https://s3/presigned-1.png")));

		assertThat(verdict.approved()).isTrue();
		assertThat(verdict.rejectionReason()).isNull();
		assertThat(verdict.hasContext()).isTrue();
		assertThat(verdict.hasObjective()).isTrue();
		assertThat(verdict.hasNuance()).isFalse();
		assertThat(verdict.hasTone()).isFalse();
		assertThat(verdict.hasExamples()).isTrue();
		assertThat(verdict.hasExecution()).isFalse();
		assertThat(verdict.hasRoleAssignment()).isTrue();
		ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
		verify(chatModel).call(captor.capture());
		UserMessage userMessage = (UserMessage) captor.getValue().getInstructions().stream()
			.filter(UserMessage.class::isInstance)
			.findFirst()
			.orElseThrow();
		// request()는 imageUrls가 비어있지 않으면 thumbnailUrl도 함께 채우므로 media는 썸네일 1 + 이미지 1 = 2개다.
		assertThat(userMessage.getMedia()).hasSize(2);
	}

	@Test
	@DisplayName("반려 응답을 파싱해 사유를 그대로 담는다")
	void inspect_rejected_parsesReason() {
		ChatModel chatModel = mock(ChatModel.class);
		given(chatModel.call(any(Prompt.class))).willReturn(textResponse(
			"{\"approved\":false,\"rejectionReason\":\"금지 콘텐츠 포함\","
				+ "\"hasContext\":false,\"hasObjective\":false,\"hasNuance\":false,"
				+ "\"hasTone\":false,\"hasExamples\":false,\"hasExecution\":false,\"hasRoleAssignment\":false}"));
		SpringAiProductInspectionAgent agent = new SpringAiProductInspectionAgent(
			chatModel, new InspectionPromptFactory(), properties());

		InspectionVerdict verdict = agent.inspect(request(List.of()));

		assertThat(verdict.approved()).isFalse();
		assertThat(verdict.rejectionReason()).isEqualTo("금지 콘텐츠 포함");
		assertThat(verdict.hasContext()).isFalse();
	}

	@Test
	@DisplayName("모델 응답이 없으면 AiException을 던진다")
	void inspect_noAssistantMessage_throwsAiException() {
		ChatModel chatModel = mock(ChatModel.class);
		given(chatModel.call(any(Prompt.class))).willReturn(ChatResponse.builder().generations(List.of()).build());
		SpringAiProductInspectionAgent agent = new SpringAiProductInspectionAgent(
			chatModel, new InspectionPromptFactory(), properties());

		assertThatThrownBy(() -> agent.inspect(request(List.of()))).isInstanceOf(AiException.class);
	}

	@Test
	@DisplayName("설정된 reasoningEffort와 maxCompletionTokens를 옵션에 전달한다")
	void inspect_passesReasoningEffortAndMaxCompletionTokensFromProperties() {
		ChatModel chatModel = mock(ChatModel.class);
		given(chatModel.call(any(Prompt.class))).willReturn(textResponse(
			"{\"approved\":true,\"rejectionReason\":null,"
				+ "\"hasContext\":true,\"hasObjective\":true,\"hasNuance\":true,"
				+ "\"hasTone\":true,\"hasExamples\":true,\"hasExecution\":true,\"hasRoleAssignment\":true}"));
		SpringAiProductInspectionAgent agent = new SpringAiProductInspectionAgent(
			chatModel, new InspectionPromptFactory(), properties());

		agent.inspect(request(List.of()));

		ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
		verify(chatModel).call(captor.capture());
		OpenAiChatOptions options = (OpenAiChatOptions) captor.getValue().getOptions();
		assertThat(options.getReasoningEffort()).isEqualTo("none");
		assertThat(options.getMaxCompletionTokens()).isEqualTo(2000);
	}

	private ChatResponse textResponse(String text) {
		return ChatResponse.builder()
			.generations(List.of(new Generation(new AssistantMessage(text))))
			.build();
	}

	private ProductInspectionRequest request(List<String> imageUrls) {
		return new ProductInspectionRequest(
			PRODUCT_ID, "PROMPT", "제목", "설명", "내용", List.of("tag1"),
			imageUrls.isEmpty() ? null : "https://s3/presigned-thumb.png", imageUrls, null);
	}

	private AiSettlementProperties properties() {
		return new AiSettlementProperties(
			"gpt-5.6-luna", "none", 2000, 8000,
			java.time.Duration.ofSeconds(90), java.time.Duration.ofSeconds(3),
			new AiSettlementProperties.Execution(4),
			new AiSettlementProperties.Conversation(java.time.Duration.ofHours(24), 20),
			new AiSettlementProperties.Sse(java.time.Duration.ofSeconds(15)),
			new AiSettlementProperties.Settlement(new AiSettlementProperties.Chat(false)),
			"token");
	}
}
