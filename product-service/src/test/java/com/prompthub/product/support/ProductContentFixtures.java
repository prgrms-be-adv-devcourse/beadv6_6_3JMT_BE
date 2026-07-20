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
		return new ProductContent(
			ProductType.PROMPT, name, "설명", "model", AmountType.PAID, amount,
			null, List.of(), "content", null, null, List.of()
		);
	}

	public static ProductContent notionContent(String name, int amount) {
		return new ProductContent(
			ProductType.NOTION, name, "새 설명", "model2", AmountType.PAID, amount,
			null, List.of(), null, null, "https://notion.so/x", List.of()
		);
	}

	public static ProductContent pptContent() {
		return new ProductContent(
			ProductType.PPT, "제목", "설명", "model", AmountType.PAID, 1000,
			null, List.of(), null, "products/1/file/a.pptx", null, List.of()
		);
	}
}
