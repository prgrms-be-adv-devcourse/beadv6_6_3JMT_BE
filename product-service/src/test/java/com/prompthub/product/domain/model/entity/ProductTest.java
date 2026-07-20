package com.prompthub.product.domain.model.entity;

import static com.prompthub.product.support.ProductContentFixtures.notionContent;
import static com.prompthub.product.support.ProductContentFixtures.pptContent;
import static com.prompthub.product.support.ProductContentFixtures.promptContent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.prompthub.product.domain.model.enums.AmountType;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.model.enums.ProductType;
import com.prompthub.product.domain.model.vo.ProductContent;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ProductTest {

	@Test
	void create_withoutCategory_setsProductType() {
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), new ProductContent(
			ProductType.PROMPT, "상품명", "설명", "model", AmountType.PAID, 1000,
			null, List.of(), "content", null, null, List.of("tag1", "tag2")
		));

		assertThat(product.getProductType()).isEqualTo(ProductType.PROMPT);
		assertThat(product.getTags()).containsExactly("tag1", "tag2");
	}

	@Test
	void update_isMajor_bumpsMajorVersionAndSetsPendingReview() {
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent());

		product.update(notionContent("새 제목", 2000), "변경 사유", true);

		assertThat(product.getProductType()).isEqualTo(ProductType.NOTION);
		assertThat(product.getMajorVersion()).isEqualTo((short) 2);
		assertThat(product.getPatchVersion()).isEqualTo((short) 0);
	}

	@Test
	void familyRootId_returnsSelfId_whenRoot() {
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent());

		assertThat(product.familyRootId()).isEqualTo(product.getId());
		assertThat(product.isFamilyRoot()).isTrue();
	}

	@Test
	void nextVersion_major_createsPendingReviewChildLinkedToFamilyRoot() {
		Product onSale = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent());
		ReflectionTestUtils.setField(onSale, "status", ProductStatus.ON_SALE);
		ReflectionTestUtils.setField(onSale, "majorVersion", (short) 2);
		ReflectionTestUtils.setField(onSale, "patchVersion", (short) 3);

		Product next = onSale.nextVersion(true, notionContent("새 제목", 2000), "메이저 변경");

		assertThat(next.getId()).isNotEqualTo(onSale.getId());
		assertThat(next.getParentId()).isEqualTo(onSale.familyRootId());
		assertThat(next.getMajorVersion()).isEqualTo((short) 3);
		assertThat(next.getPatchVersion()).isEqualTo((short) 0);
		assertThat(next.getStatus()).isEqualTo(ProductStatus.PENDING_REVIEW);
		assertThat(next.getName()).isEqualTo("새 제목");
		// 승인 전까지 기존 ON_SALE row는 변경되지 않는다
		assertThat(onSale.getStatus()).isEqualTo(ProductStatus.ON_SALE);
		assertThat(onSale.getName()).isEqualTo("제목");
	}

	@Test
	void nextVersion_patch_createsOnSaleChildAndKeepsMajorVersion() {
		Product onSale = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent());
		ReflectionTestUtils.setField(onSale, "status", ProductStatus.ON_SALE);
		ReflectionTestUtils.setField(onSale, "majorVersion", (short) 2);
		ReflectionTestUtils.setField(onSale, "patchVersion", (short) 3);

		Product next = onSale.nextVersion(false, promptContent("제목", 1500), null);

		assertThat(next.getMajorVersion()).isEqualTo((short) 2);
		assertThat(next.getPatchVersion()).isEqualTo((short) 4);
		assertThat(next.getStatus()).isEqualTo(ProductStatus.ON_SALE);
		assertThat(next.getAmount()).isEqualTo(1500);
	}

	@Test
	void supersede_onSaleRow_transitionsToSuperseded() {
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent());
		ReflectionTestUtils.setField(product, "status", ProductStatus.ON_SALE);

		product.supersede();

		assertThat(product.getStatus()).isEqualTo(ProductStatus.SUPERSEDED);
	}

	@Test
	void supersede_nonOnSaleRow_throws() {
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent());

		assertThatThrownBy(product::supersede).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void restoreFromSuperseded_supersededRow_transitionsToOnSale() {
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent());
		ReflectionTestUtils.setField(product, "status", ProductStatus.SUPERSEDED);

		product.restoreFromSuperseded();

		assertThat(product.getStatus()).isEqualTo(ProductStatus.ON_SALE);
	}

	@Test
	void create_notion_withExternalUrl_succeeds() {
		Product product = Product.create(
			UUID.randomUUID(), UUID.randomUUID(), notionContent("제목", 1000)
		);
		assertThat(product.getExternalUrl()).isEqualTo("https://notion.so/x");
	}

	@Test
	void create_ppt_withFileUrl_succeeds() {
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), pptContent());
		assertThat(product.getFileUrl()).isEqualTo("products/1/file/a.pptx");
	}
}
