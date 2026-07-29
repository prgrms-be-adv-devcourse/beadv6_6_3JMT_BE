package com.prompthub.product.domain.model.entity;

import static com.prompthub.product.support.ProductContentFixtures.notionContent;
import static com.prompthub.product.support.ProductContentFixtures.pptContent;
import static com.prompthub.product.support.ProductContentFixtures.promptContent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.prompthub.product.domain.model.enums.AmountType;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.model.enums.ProductType;
import com.prompthub.product.domain.model.vo.InspectionChecklist;
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
	void approve_pendingReview_transitionsToOnSaleAndStoresChecklist() {
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent());
		ReflectionTestUtils.setField(product, "status", ProductStatus.PENDING_REVIEW);

		product.approve(new InspectionChecklist(true, true, false, true, false, true, false));

		assertThat(product.getStatus()).isEqualTo(ProductStatus.ON_SALE);
		assertThat(product.isHasContext()).isTrue();
		assertThat(product.isHasNuance()).isFalse();
		assertThat(product.isHasRoleAssignment()).isFalse();
	}

	@Test
	void approve_nonPendingReview_throws() {
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent());

		assertThatThrownBy(() -> product.approve(emptyChecklist())).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void reject_pendingReview_transitionsToRejectedWithReasonAndStoresChecklist() {
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent());
		ReflectionTestUtils.setField(product, "status", ProductStatus.PENDING_REVIEW);

		product.reject("금지 콘텐츠 포함", new InspectionChecklist(false, true, false, false, true, false, true));

		assertThat(product.getStatus()).isEqualTo(ProductStatus.REJECTED);
		assertThat(product.getRejectionReason()).isEqualTo("금지 콘텐츠 포함");
		assertThat(product.isHasObjective()).isTrue();
		assertThat(product.isHasContext()).isFalse();
	}

	@Test
	void reject_nonPendingReview_throws() {
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent());

		assertThatThrownBy(() -> product.reject("사유", emptyChecklist())).isInstanceOf(IllegalStateException.class);
	}

	private static InspectionChecklist emptyChecklist() {
		return new InspectionChecklist(false, false, false, false, false, false, false);
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

	// ── contentHash — PENDING_REVIEW로 가는 세 경로가 모두 채우는지 ──────────────────
	//
	// submitForReview()에만 두면 update(MAJOR)·nextVersion(MAJOR)이 샌다. 그 경로로 올라온
	// 상품은 검사도 안 받고, 해시가 없어 나중에 남이 복제해도 대조에 걸리지 않는다.
	// #378에서 같은 함정을 밟았으므로 세 경로를 각각 고정한다.

	@Test
	void create_상품등록시_contentHash가_채워진다() {
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), prompt("본문 A"));

		assertThat(product.getContentHash()).hasSize(64);
	}

	@Test
	void update_MAJOR_수정시_contentHash가_새_본문으로_갱신된다() {
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), prompt("본문 A"));
		String before = product.getContentHash();

		product.update(prompt("본문 B"), "본문 개정", true);

		assertThat(product.getStatus()).isEqualTo(ProductStatus.PENDING_REVIEW);
		assertThat(product.getContentHash()).hasSize(64).isNotEqualTo(before);
	}

	@Test
	void nextVersion_MAJOR_버전업시_새_row에_contentHash가_채워진다() {
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), prompt("본문 A"));
		ReflectionTestUtils.setField(product, "status", ProductStatus.ON_SALE);

		Product next = product.nextVersion(true, prompt("본문 B"), "본문 개정");

		assertThat(next.getStatus()).isEqualTo(ProductStatus.PENDING_REVIEW);
		assertThat(next.getContentHash()).hasSize(64).isNotEqualTo(product.getContentHash());
	}

	@Test
	void contentHash는_제목만_바뀌면_그대로다() {
		// 베낀 사람이 제목을 자기 것으로 바꿔도 복제로 잡혀야 한다.
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), prompt("같은 본문"));
		String before = product.getContentHash();

		product.update(new ProductContent(
			ProductType.PROMPT, "바뀐 제목", "바뀐 소개글", "model", AmountType.PAID, 1000,
			null, List.of(), "같은 본문", null, null, List.of("tag")), "제목만 수정", false);

		assertThat(product.getContentHash()).isEqualTo(before);
	}

	@Test
	void contentHash는_본문이_없는_유형에서_null이다() {
		Product notion = Product.create(UUID.randomUUID(), UUID.randomUUID(), notionContent("제목", 1000));
		Product ppt = Product.create(UUID.randomUUID(), UUID.randomUUID(), pptContent());

		assertThat(notion.getContentHash()).isNull();
		assertThat(ppt.getContentHash()).isNull();
	}

	private ProductContent prompt(String content) {
		return new ProductContent(
			ProductType.PROMPT, "제목", "설명", "model", AmountType.PAID, 1000,
			null, List.of(), content, null, null, List.of("tag"));
	}
}
