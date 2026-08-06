package com.prompthub.search.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.vo.ProductContent;
import com.prompthub.product.domain.model.enums.AmountType;
import com.prompthub.product.domain.model.enums.ProductType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class EmbeddingSourceTest {

	@Nested
	@DisplayName("원문 구성")
	class Text {

		@Test
		@DisplayName("제목·태그·설명·본문을 순서대로 합친다")
		void combinesFields() {
			Product product = product("엑셀 자동화", "보고서를 만들어준다", "본문입니다", List.of("생산성", "AI"));

			String text = EmbeddingSource.of(product).text();

			assertThat(text).isEqualTo("엑셀 자동화\n생산성 AI\n보고서를 만들어준다\n본문입니다");
		}

		@Test
		@DisplayName("본문이 없는 유형은 그 줄을 비우지 않고 아예 뺀다")
		void skipsMissingParts() {
			// NOTION은 본문 대신 외부 링크를 갖는다. 태그도 없으면 두 파트가 빠진다.
			Product product = notionProduct("노션 템플릿", "회의록 정리");

			String text = EmbeddingSource.of(product).text();

			assertThat(text).isEqualTo("노션 템플릿\n회의록 정리");
		}

		@Test
		@DisplayName("본문이 길면 잘라낸다")
		void truncatesLongContent() {
			String longContent = "가".repeat(EmbeddingSource.MAX_CONTENT_CHARS + 500);
			Product product = product("이름", "설명", longContent, List.of());

			String text = EmbeddingSource.of(product).text();

			// 모델 입력 상한(8,191토큰)을 넘기지 않으려는 것이지 정확한 토큰 계산이 목적이 아니다.
			assertThat(text).hasSize("이름".length() + 1 + "설명".length() + 1 + EmbeddingSource.MAX_CONTENT_CHARS);
		}
	}

	@Nested
	@DisplayName("해시")
	class Hash {

		@Test
		@DisplayName("원문이 같으면 해시도 같다")
		void sameTextSameHash() {
			Product a = product("이름", "설명", "본문", List.of("태그"));
			Product b = product("이름", "설명", "본문", List.of("태그"));

			assertThat(EmbeddingSource.of(a).hash()).isEqualTo(EmbeddingSource.of(b).hash());
		}

		@Test
		@DisplayName("원문이 한 글자만 달라도 해시가 달라진다")
		void differentTextDifferentHash() {
			Product a = product("이름", "설명", "본문", List.of("태그"));
			Product b = product("이름", "설명", "본문!", List.of("태그"));

			assertThat(EmbeddingSource.of(a).hash()).isNotEqualTo(EmbeddingSource.of(b).hash());
		}

		@Test
		@DisplayName("해시는 컬럼 길이(64자)에 들어간다")
		void fitsColumn() {
			Product product = product("이름", "설명", "본문", List.of("태그"));

			assertThat(EmbeddingSource.of(product).hash()).hasSize(64);
		}
	}

	/** PROMPT — 본문(content)이 인라인 텍스트로 있는 유일한 유형. */
	private Product product(String name, String description, String content, List<String> tags) {
		ProductContent productContent = new ProductContent(
			ProductType.PROMPT, name, description, "gpt-5", AmountType.PAID, 1000,
			null, List.of(), content, null, null, tags);
		return Product.create(UUID.randomUUID(), UUID.randomUUID(), productContent);
	}

	/** NOTION — 본문 대신 외부 링크를 갖는다. content가 null이면 PROMPT로는 만들 수 없다. */
	private Product notionProduct(String name, String description) {
		ProductContent productContent = new ProductContent(
			ProductType.NOTION, name, description, "model", AmountType.PAID, 1000,
			null, List.of(), null, null, "https://notion.so/x", List.of());
		return Product.create(UUID.randomUUID(), UUID.randomUUID(), productContent);
	}
}
