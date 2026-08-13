package com.prompthub.search.application.embedding;

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
		@DisplayName("프롬프트는 제목·태그·설명·모델만 순서대로 합친다")
		void combinesFields() {
			Product product = product("엑셀 자동화", "보고서를 만들어준다", "본문입니다", List.of("생산성", "AI"));

			String text = EmbeddingSource.of(product).text();

			assertThat(text).isEqualTo("엑셀 자동화\n생산성 AI\n보고서를 만들어준다\ngpt-5");
		}

		@Test
		@DisplayName("본문이 없는 유형은 그 줄을 비우지 않고 아예 뺀다")
		void skipsMissingParts() {
			// NOTION은 본문 대신 외부 링크를 갖는다. 태그도 없으면 두 파트가 빠진다.
			Product product = notionProduct("노션 템플릿", "회의록 정리");

			String text = EmbeddingSource.of(product).text();

			assertThat(text).isEqualTo("노션 템플릿\n회의록 정리");
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
		@DisplayName("본문만 달라지면 검색 원문 해시는 유지된다")
		void contentDoesNotChangeHash() {
			Product a = product("이름", "설명", "본문", List.of("태그"));
			Product b = product("이름", "설명", "본문!", List.of("태그"));

			assertThat(EmbeddingSource.of(a).hash()).isEqualTo(EmbeddingSource.of(b).hash());
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
