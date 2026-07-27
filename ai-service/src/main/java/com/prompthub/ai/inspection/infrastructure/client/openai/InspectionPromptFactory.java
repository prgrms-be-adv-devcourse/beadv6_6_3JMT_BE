package com.prompthub.ai.inspection.infrastructure.client.openai;

import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

import com.prompthub.ai.inspection.domain.model.InspectionVerdict;

@Component
public class InspectionPromptFactory {

	private static final String SYSTEM_PROMPT = """
			당신은 PromptHub 오픈마켓의 상품 등록 검수 담당자다.
			판매자가 등록한 상품의 텍스트(이름/설명/본문/태그)와 이미지(썸네일/상세 이미지)를 보고
			아래 두 기준 중 하나라도 위반하면 반려한다.
			1. 금지/불법 콘텐츠: 저작권 침해, 불법 복제물, 음란물/성인물, 사기·허위 광고.
			2. 스팸/저품질 콘텐츠: 의미 없는 텍스트, 상품과 무관한 텍스트/이미지, 극단적으로 불성실한 설명.
			위반 여부가 확실하지 않으면 애매한 케이스도 반려로 판단한다(보수적 기본값) —
			정상 상품을 오탐 반려하는 것보다 위반 콘텐츠를 통과시키는 위험을 더 크게 본다.
			반려 시 rejectionReason은 판매자가 이해할 수 있는 한국어 한 문장으로 구체적인 사유를 적는다.
			승인 시 rejectionReason은 null로 둔다.
			""";

	public String systemPrompt() {
		return SYSTEM_PROMPT;
	}

	public String formatInstructions(BeanOutputConverter<InspectionVerdict> converter) {
		return converter.getFormat();
	}

	public String userPrompt(String productType, String name, String description, String content, java.util.List<String> tags) {
		return """
				상품 유형: %s
				상품명: %s
				설명: %s
				본문: %s
				태그: %s
				""".formatted(productType, name, description, content, String.join(", ", tags));
	}
}
