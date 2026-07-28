package com.prompthub.ai.inspection.infrastructure.client.openai;

import com.prompthub.ai.global.config.AiSettlementProperties;
import com.prompthub.ai.global.exception.AiErrorCode;
import com.prompthub.ai.global.exception.AiException;
import com.prompthub.ai.inspection.application.dto.ProductInspectionRequest;
import com.prompthub.ai.inspection.application.port.ProductInspectionAiPort;
import com.prompthub.ai.inspection.domain.model.InspectionVerdict;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;

@Component
public class SpringAiProductInspectionAgent implements ProductInspectionAiPort {

	// 체크리스트 7개 필드가 추가되며 판단 항목이 늘어, reasoning 모델이 추론에 토큰을 더 쓰고도
	// 답변 텍스트를 끝까지 생성할 여유가 필요해 300에서 상향했다(애매한 케이스에서 답변이 잘려 빈 응답이 되는 문제 발생).
	private static final int MAX_COMPLETION_TOKENS = 800;
	private static final Map<String, MimeType> IMAGE_MIME_TYPES = Map.of(
		"jpg", Media.Format.IMAGE_JPEG,
		"jpeg", Media.Format.IMAGE_JPEG,
		"png", Media.Format.IMAGE_PNG,
		"gif", Media.Format.IMAGE_GIF,
		"webp", Media.Format.IMAGE_WEBP
	);

	private final ChatModel chatModel;
	private final InspectionPromptFactory promptFactory;
	private final AiSettlementProperties properties;

	public SpringAiProductInspectionAgent(
		ChatModel chatModel, InspectionPromptFactory promptFactory, AiSettlementProperties properties
	) {
		this.chatModel = chatModel;
		this.promptFactory = promptFactory;
		this.properties = properties;
	}

	@Override
	public InspectionVerdict inspect(ProductInspectionRequest request) {
		BeanOutputConverter<InspectionVerdict> converter = new BeanOutputConverter<>(InspectionVerdict.class);

		List<Message> messages = new ArrayList<>();
		messages.add(new SystemMessage(promptFactory.systemPrompt()));
		messages.add(new SystemMessage(promptFactory.formatInstructions(converter)));
		messages.add(userMessage(request));

		OpenAiChatOptions options = OpenAiChatOptions.builder()
			.model(properties.model())
			.maxCompletionTokens(MAX_COMPLETION_TOKENS)
			.build();
		ChatResponse response = chatModel.call(new Prompt(messages, options));
		String text = requireAssistantText(response);
		return converter.convert(text);
	}

	private UserMessage userMessage(ProductInspectionRequest request) {
		String text = promptFactory.userPrompt(
			request.productType(), request.name(), request.description(), request.content(), request.tags());
		List<Media> media = collectMedia(request);
		return UserMessage.builder().text(text).media(media.toArray(new Media[0])).build();
	}

	private List<Media> collectMedia(ProductInspectionRequest request) {
		List<Media> media = new ArrayList<>();
		if (request.thumbnailUrl() != null) {
			toMedia(request.thumbnailUrl()).ifPresent(media::add);
		}
		for (String imageUrl : request.imageUrls()) {
			toMedia(imageUrl).ifPresent(media::add);
		}
		return media;
	}

	private java.util.Optional<Media> toMedia(String presignedUrl) {
		MimeType mimeType = guessMimeType(presignedUrl);
		if (mimeType == null) {
			return java.util.Optional.empty();
		}
		return java.util.Optional.of(new Media(mimeType, URI.create(presignedUrl)));
	}

	private MimeType guessMimeType(String presignedUrl) {
		String path = presignedUrl.split("\\?")[0];
		int dot = path.lastIndexOf('.');
		if (dot < 0) {
			return null;
		}
		String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
		return IMAGE_MIME_TYPES.get(ext);
	}

	private String requireAssistantText(ChatResponse response) {
		if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
			throw new AiException(AiErrorCode.AI_PROVIDER_UNAVAILABLE);
		}
		AssistantMessage output = response.getResult().getOutput();
		if (output.getText() == null || output.getText().isBlank()) {
			throw new AiException(AiErrorCode.AI_PROVIDER_UNAVAILABLE);
		}
		return output.getText();
	}
}
