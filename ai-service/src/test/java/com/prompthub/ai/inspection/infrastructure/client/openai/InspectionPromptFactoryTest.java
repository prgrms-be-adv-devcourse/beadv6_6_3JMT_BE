package com.prompthub.ai.inspection.infrastructure.client.openai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InspectionPromptFactoryTest {

	private final InspectionPromptFactory promptFactory = new InspectionPromptFactory();

	@Test
	@DisplayName("NOTION 상품은 본문이 null이어도 정상 문구로 대체하고 null을 그대로 노출하지 않는다")
	void userPrompt_notionWithNullContent_replacesWithNormalNotice() {
		String prompt = promptFactory.userPrompt("NOTION", "제목", "설명", null, List.of("tag1"), false);

		assertThat(prompt).doesNotContain("본문: null");
		assertThat(prompt).contains("본문 없음 — 상품 유형이 NOTION이라 정상");
	}

	@Test
	@DisplayName("PPT/EXCEL 상품도 본문이 비어 있으면 정상 문구로 대체한다")
	void userPrompt_pptOrExcelWithBlankContent_replacesWithNormalNotice() {
		String pptPrompt = promptFactory.userPrompt("PPT", "제목", "설명", "", List.of(), false);
		String excelPrompt = promptFactory.userPrompt("EXCEL", "제목", "설명", null, List.of(), false);

		assertThat(pptPrompt).contains("본문 없음 — 상품 유형이 PPT이라 정상");
		assertThat(excelPrompt).contains("본문 없음 — 상품 유형이 EXCEL이라 정상");
	}

	@Test
	@DisplayName("PROMPT 상품은 본문이 있으면 그대로 노출한다(회귀 방지)")
	void userPrompt_promptWithContent_keepsContentAsIs() {
		String prompt = promptFactory.userPrompt("PROMPT", "제목", "설명", "실제 본문", List.of("tag1"), false);

		assertThat(prompt).contains("본문: 실제 본문");
	}

	@Test
	@DisplayName("PROMPT 상품인데 본문이 null이면 대체하지 않고 null 그대로 노출한다")
	void userPrompt_promptWithNullContent_doesNotReplace() {
		String prompt = promptFactory.userPrompt("PROMPT", "제목", "설명", null, List.of(), false);

		assertThat(prompt).contains("본문: null");
	}

	@Test
	@DisplayName("무료 여부를 사용자 메시지에 예/아니오로 담는다")
	void userPrompt_includesFreeFlag() {
		String freePrompt = promptFactory.userPrompt("PROMPT", "제목", "설명", "본문", List.of(), true);
		String paidPrompt = promptFactory.userPrompt("PROMPT", "제목", "설명", "본문", List.of(), false);

		assertThat(freePrompt).contains("무료 여부: 예");
		assertThat(paidPrompt).contains("무료 여부: 아니오");
	}

	@Test
	@DisplayName("시스템 프롬프트는 AI 도구/서비스 명칭·로고 언급만으로는 저작권 침해 반려 사유가 아니라고 명시한다")
	void systemPrompt_containsToolNameMentionIsNotCopyrightInfringementException() {
		String systemPrompt = promptFactory.systemPrompt();

		assertThat(systemPrompt).contains("Claude Code");
		assertThat(systemPrompt).contains("그 사실만으로 저작권 침해로 반려하지 않는다");
	}

	@Test
	@DisplayName("시스템 프롬프트는 무료 상품이 출처를 표기한 경우 저작권 침해 반려 예외 대상이라고 명시한다")
	void systemPrompt_containsFreeProductWithSourceAttributionException() {
		String systemPrompt = promptFactory.systemPrompt();

		assertThat(systemPrompt).contains("무료 여부");
		assertThat(systemPrompt).contains("원저작자 출처");
		assertThat(systemPrompt).contains("저작권 침해로 반려하지 않는다");
	}
}
