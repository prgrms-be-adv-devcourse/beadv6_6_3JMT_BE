package com.prompthub.product.domain.model.vo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.prompthub.product.domain.model.enums.AmountType;
import com.prompthub.product.domain.model.enums.ProductType;
import com.prompthub.product.exception.ProductException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductContentTest {

	@Test
	void prompt_withFileUrl_throws() {
		assertThatThrownBy(() -> new ProductContent(
			ProductType.PROMPT, "제목", "설명", "model", AmountType.PAID, 1000,
			null, List.of(), "content", "products/x.pptx", null, List.of()
		)).isInstanceOf(ProductException.class);
	}

	@Test
	void ppt_withoutFileUrl_throws() {
		assertThatThrownBy(() -> new ProductContent(
			ProductType.PPT, "제목", "설명", "model", AmountType.PAID, 1000,
			null, List.of(), null, null, null, List.of()
		)).isInstanceOf(ProductException.class);
	}

	@Test
	void notion_withExternalUrl_succeeds() {
		ProductContent content = new ProductContent(
			ProductType.NOTION, "제목", "설명", "model", AmountType.PAID, 1000,
			null, List.of(), null, null, "https://notion.so/t", List.of()
		);
		assertThat(content.externalUrl()).isEqualTo("https://notion.so/t");
	}

	@Test
	void ppt_withFileUrl_succeeds() {
		ProductContent content = new ProductContent(
			ProductType.PPT, "제목", "설명", "model", AmountType.PAID, 1000,
			null, List.of(), null, "products/1/file/a.pptx", null, List.of()
		);
		assertThat(content.fileUrl()).isEqualTo("products/1/file/a.pptx");
	}

	@Test
	void nullImageUrlsAndTags_normalizedToEmptyLists() {
		ProductContent content = new ProductContent(
			ProductType.PROMPT, "제목", "설명", "model", AmountType.PAID, 1000,
			null, null, "content", null, null, null
		);
		assertThat(content.imageUrls()).isEmpty();
		assertThat(content.tags()).isEmpty();
	}

	@Test
	void builder_defensivelyCopiesCollections() {
		List<String> imageUrls = new ArrayList<>(List.of("image.png"));
		List<String> tags = new ArrayList<>(List.of("tag"));
		ProductContent content = ProductContent.builder()
			.productType(ProductType.PROMPT)
			.name("제목")
			.description("설명")
			.model("model")
			.amountType(AmountType.PAID)
			.amount(1000)
			.imageUrls(imageUrls)
			.content("content")
			.tags(tags)
			.build();

		imageUrls.add("changed.png");
		tags.add("changed");

		assertThat(content.imageUrls()).containsExactly("image.png");
		assertThat(content.tags()).containsExactly("tag");
		assertThatThrownBy(() -> content.imageUrls().add("blocked.png"))
			.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> content.tags().add("blocked"))
			.isInstanceOf(UnsupportedOperationException.class);
	}
}
