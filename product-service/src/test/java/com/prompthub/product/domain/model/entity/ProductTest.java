package com.prompthub.product.domain.model.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.product.domain.model.enums.AmountType;
import com.prompthub.product.domain.model.enums.ProductType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductTest {

	@Test
	void create_withoutCategory_setsProductType() {
		Product product = Product.create(
			UUID.randomUUID(),
			UUID.randomUUID(),
			ProductType.PROMPT,
			"상품명",
			"설명",
			"model",
			AmountType.PAID,
			1000,
			null,
			List.of(),
			"content",
			List.of("tag1", "tag2")
		);

		assertThat(product.getProductType()).isEqualTo(ProductType.PROMPT);
		assertThat(product.getTags()).containsExactly("tag1", "tag2");
	}

	@Test
	void update_isMajor_bumpsMajorVersionAndSetsPendingReview() {
		Product product = Product.create(
			UUID.randomUUID(), UUID.randomUUID(), ProductType.PROMPT,
			"제목", "설명", "model", AmountType.PAID, 1000,
			null, List.of(), "content", List.of()
		);

		product.update(
			ProductType.NOTION, "새 제목", "새 설명", "model2", AmountType.PAID, 2000,
			null, List.of(), "content2", List.of(), "변경 사유", true
		);

		assertThat(product.getProductType()).isEqualTo(ProductType.NOTION);
		assertThat(product.getMajorVersion()).isEqualTo((short) 2);
		assertThat(product.getPatchVersion()).isEqualTo((short) 0);
	}
}
