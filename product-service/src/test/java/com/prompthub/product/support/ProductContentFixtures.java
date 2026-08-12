package com.prompthub.product.support;

import com.prompthub.product.domain.model.enums.AmountType;
import com.prompthub.product.domain.model.enums.ProductType;
import com.prompthub.product.domain.model.vo.ProductContent;
import java.util.List;

/** 테스트 전용 ProductContent 픽스처 — 필드 변경 시 이 클래스만 수정하면 된다. */
public final class ProductContentFixtures {

	private ProductContentFixtures() {
	}

	public static ProductContent promptContent() {
		return promptContent("제목", 1000);
	}

	public static ProductContent promptContent(String name, int amount) {
		return promptContent(name, amount, "content");
	}

	public static ProductContent promptContent(String name, int amount, String content) {
		return productContent(ProductType.PROMPT, name, amount)
			.content(content)
			.build();
	}

	public static ProductContent freePromptContent() {
		return productContent(ProductType.PROMPT, "제목", 0)
			.amountType(AmountType.FREE)
			.content("content")
			.build();
	}

	public static ProductContent notionContent(String name, int amount) {
		return productContent(ProductType.NOTION, name, amount)
			.description("새 설명")
			.model("model2")
			.externalUrl("https://notion.so/x")
			.build();
	}

	public static ProductContent pptContent() {
		return productContent(ProductType.PPT, "제목", 1000)
			.fileUrl("products/1/file/a.pptx")
			.build();
	}

	private static ProductContent.ProductContentBuilder productContent(
		ProductType productType, String name, int amount
	) {
		return ProductContent.builder()
			.productType(productType)
			.name(name)
			.description("설명")
			.model("model")
			.amountType(AmountType.PAID)
			.amount(amount)
			.imageUrls(List.of())
			.tags(List.of());
	}
}
