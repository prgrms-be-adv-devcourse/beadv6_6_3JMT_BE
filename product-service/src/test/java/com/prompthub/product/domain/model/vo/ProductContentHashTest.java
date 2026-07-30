package com.prompthub.product.domain.model.vo;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.product.domain.model.enums.AmountType;
import com.prompthub.product.domain.model.enums.ProductType;
import com.prompthub.product.support.ProductContentFixtures;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProductContentHashTest {

	@Test
	@DisplayName("PROMPT 해시는 SHA-256 hex 64자다")
	void promptHashFitsColumn() {
		String hash = ProductContentHash.of(prompt("본문입니다"));

		assertThat(hash).hasSize(64);
	}

	@Test
	@DisplayName("본문이 같으면 해시도 같다")
	void sameContentSameHash() {
		assertThat(ProductContentHash.of(prompt("같은 본문")))
			.isEqualTo(ProductContentHash.of(prompt("같은 본문")));
	}

	@Test
	@DisplayName("본문이 한 글자만 달라도 해시가 달라진다")
	void differentContentDifferentHash() {
		assertThat(ProductContentHash.of(prompt("본문입니다")))
			.isNotEqualTo(ProductContentHash.of(prompt("본문입니다!")));
	}

	@Test
	@DisplayName("공백·대소문자만 다르면 해시가 같다")
	void normalizesWhitespaceAndCase() {
		assertThat(ProductContentHash.of(prompt("Hello   World\n\tGPT  ")))
			.isEqualTo(ProductContentHash.of(prompt("hello world gpt")));
	}

	@Test
	@DisplayName("선행·후행 전각 공백(U+3000)은 지우지 않는다 — ASCII 공백만 정규화 대상이라 중간과 동일하게 남긴다")
	void doesNotStripFullWidthSpaceAtEdges() {
		// String.strip()(유니코드 인식)을 썼다면 앞뒤 U+3000이 지워져 두 해시가 같아졌을 것이다.
		// V5 백필 SQL도 U+3000을 공백으로 안 보므로, Java도 앞뒤·중간을 똑같이 안 지워야 일치한다.
		assertThat(ProductContentHash.of(prompt("　Hello　World　")))
			.isNotEqualTo(ProductContentHash.of(prompt("Hello　World")));
	}

	@Test
	@DisplayName("제목·소개글이 달라도 본문이 같으면 해시가 같다")
	void ignoresNameAndDescription() {
		// 이게 이 해시의 계약이다. 베낀 사람이 제목을 자기 것으로 바꿔도 복제로 잡혀야 한다.
		ProductContent a = new ProductContent(
			ProductType.PROMPT, "원본 제목", "원본 소개글", "gpt-5", AmountType.PAID, 1000,
			null, List.of(), "같은 본문", null, null, List.of("태그"));
		ProductContent b = new ProductContent(
			ProductType.PROMPT, "베낀 제목", "베낀 소개글", "claude", AmountType.FREE, 0,
			null, List.of(), "같은 본문", null, null, List.of("다른태그"));

		assertThat(ProductContentHash.of(a)).isEqualTo(ProductContentHash.of(b));
	}

	@Test
	@DisplayName("본문이 없는 유형은 null이다 — 비교 대상이 아니다")
	void nullForTypesWithoutContent() {
		assertThat(ProductContentHash.of(ProductContentFixtures.notionContent("노션 상품", 1000))).isNull();
		assertThat(ProductContentHash.of(ProductContentFixtures.pptContent())).isNull();
	}

	private ProductContent prompt(String content) {
		return new ProductContent(
			ProductType.PROMPT, "제목", "소개글", "gpt-5", AmountType.PAID, 1000,
			null, List.of(), content, null, null, List.of("태그"));
	}
}
