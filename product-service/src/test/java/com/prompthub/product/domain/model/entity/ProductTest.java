package com.prompthub.product.domain.model.entity;

import static com.prompthub.product.support.ProductContentFixtures.freePromptContent;
import static com.prompthub.product.support.ProductContentFixtures.notionContent;
import static com.prompthub.product.support.ProductContentFixtures.pptContent;
import static com.prompthub.product.support.ProductContentFixtures.promptContent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.prompthub.product.domain.model.enums.AmountType;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.model.enums.ProductType;
import com.prompthub.product.domain.model.enums.ProductVersionType;
import com.prompthub.product.domain.model.vo.InspectionChecklist;
import com.prompthub.product.domain.model.vo.ProductContent;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
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
	void updateDraftContent_draftRow_updatesContentOnly_keepsVersionAndStatus() {
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent());

		product.updateDraftContent(notionContent("새 제목", 2000));

		assertThat(product.getProductType()).isEqualTo(ProductType.NOTION);
		assertThat(product.getName()).isEqualTo("새 제목");
		// 임시저장은 검수 전이라 버전·상태를 그대로 둔다 — DRAFT를 몇 번 고쳐도 1.0/DRAFT다.
		assertThat(product.getMajorVersion()).isEqualTo((short) 1);
		assertThat(product.getPatchVersion()).isEqualTo((short) 0);
		assertThat(product.getStatus()).isEqualTo(ProductStatus.DRAFT);
	}

	@Test
	void updateDraftContent_nonDraftRow_throws() {
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent());
		ReflectionTestUtils.setField(product, "status", ProductStatus.ON_SALE);

		assertThatThrownBy(() -> product.updateDraftContent(promptContent()))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void familyRootId_returnsSelfId_whenRoot() {
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent());

		assertThat(product.familyRootId()).isEqualTo(product.getId());
		assertThat(product.isFamilyRoot()).isTrue();
	}

	@Test
	void createNextVersion_major_createsPendingReviewChildLinkedToFamilyRoot() {
		Product onSale = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent());
		ReflectionTestUtils.setField(onSale, "status", ProductStatus.ON_SALE);
		ReflectionTestUtils.setField(onSale, "majorVersion", (short) 2);
		ReflectionTestUtils.setField(onSale, "patchVersion", (short) 3);
		UUID nextId = UUID.randomUUID();

		Product next = onSale.createNextVersion(nextId, ProductVersionType.MAJOR, notionContent("새 제목", 2000), "메이저 변경");

		// id는 application이 만들어 넘긴 값 그대로다 — 도메인이 새로 생성하지 않는다.
		assertThat(next.getId()).isEqualTo(nextId);
		assertThat(next.getParentId()).isEqualTo(onSale.familyRootId());
		assertThat(next.getMajorVersion()).isEqualTo((short) 3);
		assertThat(next.getPatchVersion()).isEqualTo((short) 0);
		assertThat(next.getStatus()).isEqualTo(ProductStatus.PENDING_REVIEW);
		assertThat(next.getName()).isEqualTo("새 제목");
		// MAJOR row도 새 검수 회차 시작이다 — stale 재발행 후보 판정 기준.
		assertThat(next.getInspectionRequestedAt()).isNotNull();
		assertThat(next.getInspectionRequestRetryCount()).isZero();
		// 승인 전까지 기존 ON_SALE row는 변경되지 않는다
		assertThat(onSale.getStatus()).isEqualTo(ProductStatus.ON_SALE);
		assertThat(onSale.getName()).isEqualTo("제목");
	}

	@Test
	void createNextVersion_patch_createsOnSaleChildAndKeepsMajorVersion() {
		Product onSale = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent());
		ReflectionTestUtils.setField(onSale, "status", ProductStatus.ON_SALE);
		ReflectionTestUtils.setField(onSale, "majorVersion", (short) 2);
		ReflectionTestUtils.setField(onSale, "patchVersion", (short) 3);

		Product next = onSale.createNextVersion(
			UUID.randomUUID(), ProductVersionType.PATCH, promptContent("제목", 1500), null);

		assertThat(next.getMajorVersion()).isEqualTo((short) 2);
		assertThat(next.getPatchVersion()).isEqualTo((short) 4);
		assertThat(next.getStatus()).isEqualTo(ProductStatus.ON_SALE);
		assertThat(next.getAmount()).isEqualTo(1500);
		// PATCH는 검수를 거치지 않으므로 검수 요청 회차를 시작하지 않는다.
		assertThat(next.getInspectionRequestedAt()).isNull();
		assertThat(next.getInspectionRequestRetryCount()).isZero();
	}

	@Test
	void updateRejectedContent_rejectedRow_updatesContentOnly_keepsVersionStatusAndReasons() {
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent());
		ReflectionTestUtils.setField(product, "status", ProductStatus.REJECTED);
		ReflectionTestUtils.setField(product, "majorVersion", (short) 3);
		ReflectionTestUtils.setField(product, "patchVersion", (short) 0);
		ReflectionTestUtils.setField(product, "changeReason", "이전 변경 사유");
		ReflectionTestUtils.setField(product, "rejectionReason", "금지 콘텐츠 포함");
		UUID id = product.getId();

		product.updateRejectedContent(notionContent("보정된 제목", 2000));

		assertThat(product.getId()).isEqualTo(id);
		assertThat(product.getMajorVersion()).isEqualTo((short) 3);
		assertThat(product.getPatchVersion()).isEqualTo((short) 0);
		assertThat(product.getStatus()).isEqualTo(ProductStatus.REJECTED);
		assertThat(product.getChangeReason()).isEqualTo("이전 변경 사유");
		assertThat(product.getRejectionReason()).isEqualTo("금지 콘텐츠 포함");
		assertThat(product.getProductType()).isEqualTo(ProductType.NOTION);
		assertThat(product.getName()).isEqualTo("보정된 제목");
	}

	@Test
	void updateRejectedContent_nonRejectedRow_throws() {
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent());
		ReflectionTestUtils.setField(product, "status", ProductStatus.ON_SALE);

		assertThatThrownBy(() -> product.updateRejectedContent(promptContent()))
			.isInstanceOf(IllegalStateException.class);
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

	// startInspectionRequest()가 검수 요청 시작 시각을 기록하고 재발행 횟수를 초기화하는지 검증한다
	// (2026-08-05 로드맵 PR4) — stale 재발행 스케줄러가 이 값들로 오래 대기한 상품을 찾는다.
	@Test
	void submitForReview_draft_startsInspectionRequestWithRetryCountZero() {
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent());

		product.submitForReview();

		assertThat(product.getStatus()).isEqualTo(ProductStatus.PENDING_REVIEW);
		assertThat(product.getInspectionRequestedAt()).isNotNull();
		assertThat(product.getInspectionRequestRetryCount()).isZero();
	}

	@Test
	void submitForReview_rejectedWithPriorRetry_startsNewInspectionRequestAndResetsRetryCount() {
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent());
		ReflectionTestUtils.setField(product, "status", ProductStatus.REJECTED);
		ReflectionTestUtils.setField(product, "inspectionRequestRetryCount", 1);

		product.submitForReview();

		// 새 검수 회차이므로 이전 회차의 재발행 횟수를 그대로 물려받지 않는다.
		assertThat(product.getInspectionRequestRetryCount()).isZero();
	}

	@Test
	void submitForReview_nonDraftOrRejected_throws() {
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent());
		ReflectionTestUtils.setField(product, "status", ProductStatus.ON_SALE);

		assertThatThrownBy(product::submitForReview).isInstanceOf(IllegalStateException.class);
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
		assertThat(product.isChecklistRecorded()).isTrue();
	}

	@Test
	void create_beforeInspection_checklistNotRecorded() {
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent());

		assertThat(product.isChecklistRecorded()).isFalse();
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
		assertThat(product.isChecklistRecorded()).isTrue();
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

	// ── contentHash — PENDING_REVIEW로 가는 두 경로가 모두 채우는지 ──────────────────
	//
	// submitForReview()에만 두면 createNextVersion(MAJOR)이 샌다. 그 경로로 올라온 상품은
	// 검사도 안 받고, 해시가 없어 나중에 남이 복제해도 대조에 걸리지 않는다. #378에서 같은
	// 함정을 밟았으므로 두 경로를 각각 고정한다.

	@Test
	void create_상품등록시_contentHash가_채워진다() {
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), prompt("본문 A"));

		assertThat(product.getContentHash()).hasSize(64);
	}

	@Test
	void createNextVersion_MAJOR_버전업시_새_row에_contentHash가_채워진다() {
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), prompt("본문 A"));
		ReflectionTestUtils.setField(product, "status", ProductStatus.ON_SALE);

		Product next = product.createNextVersion(UUID.randomUUID(), ProductVersionType.MAJOR, prompt("본문 B"), "본문 개정");

		assertThat(next.getStatus()).isEqualTo(ProductStatus.PENDING_REVIEW);
		assertThat(next.getContentHash()).hasSize(64).isNotEqualTo(product.getContentHash());
	}

	@Test
	void contentHash는_제목만_바뀌면_그대로다() {
		// 베낀 사람이 제목을 자기 것으로 바꿔도 복제로 잡혀야 한다.
		Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), prompt("같은 본문"));
		String before = product.getContentHash();

		product.updateDraftContent(new ProductContent(
			ProductType.PROMPT, "바뀐 제목", "바뀐 소개글", "model", AmountType.PAID, 1000,
			null, List.of(), "같은 본문", null, null, List.of("tag")));

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

	@Nested
	class DetermineVersionType {

		@Test
		void promptContentChanged_isMajor() {
			Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent("제목", 1000, "본문 A"));

			assertThat(product.determineVersionType(candidateWithContent(product, "본문 B")))
				.contains(ProductVersionType.MAJOR);
		}

		@Test
		void promptContentWhitespaceOnlyChanged_isStillMajor() {
			// 공백·줄바꿈도 원문 그대로 비교한다 — trim 등 정규화를 하지 않는다.
			Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent("제목", 1000, "본문"));

			assertThat(product.determineVersionType(candidateWithContent(product, "본문\n")))
				.contains(ProductVersionType.MAJOR);
		}

		@Test
		void notionExternalUrlChanged_isMajor() {
			Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), notionContent("제목", 1000));
			ProductContent candidate = new ProductContent(
				ProductType.NOTION, product.getName(), product.getDescription(), product.getModel(),
				product.getAmountType(), product.getAmount(), product.getThumbnailUrl(), product.getImageUrls(),
				null, null, "https://notion.so/changed", product.getTags());

			assertThat(product.determineVersionType(candidate)).contains(ProductVersionType.MAJOR);
		}

		@Test
		void pptFileChanged_isMajor() {
			Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), pptContent());
			ProductContent candidate = new ProductContent(
				ProductType.PPT, product.getName(), product.getDescription(), product.getModel(),
				product.getAmountType(), product.getAmount(), product.getThumbnailUrl(), product.getImageUrls(),
				null, "products/1/file/b.pptx", null, product.getTags());

			assertThat(product.determineVersionType(candidate)).contains(ProductVersionType.MAJOR);
		}

		@Test
		void freeToPaid_isMajor() {
			Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), freePromptContent());
			ProductContent candidate = new ProductContent(
				ProductType.PROMPT, product.getName(), product.getDescription(), product.getModel(),
				AmountType.PAID, 1000, product.getThumbnailUrl(), product.getImageUrls(),
				product.getContent(), product.getFileUrl(), product.getExternalUrl(), product.getTags());

			assertThat(product.determineVersionType(candidate)).contains(ProductVersionType.MAJOR);
		}

		@Test
		void priceChangedWithinPaid_isPatch() {
			Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent("제목", 1000, "본문"));
			ProductContent candidate = new ProductContent(
				ProductType.PROMPT, product.getName(), product.getDescription(), product.getModel(),
				AmountType.PAID, 2000, product.getThumbnailUrl(), product.getImageUrls(),
				product.getContent(), product.getFileUrl(), product.getExternalUrl(), product.getTags());

			assertThat(product.determineVersionType(candidate)).contains(ProductVersionType.PATCH);
		}

		@Test
		void titleOnlyChanged_isPatch() {
			Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent("제목", 1000, "본문"));
			ProductContent candidate = new ProductContent(
				ProductType.PROMPT, "새 제목", product.getDescription(), product.getModel(),
				product.getAmountType(), product.getAmount(), product.getThumbnailUrl(), product.getImageUrls(),
				product.getContent(), product.getFileUrl(), product.getExternalUrl(), product.getTags());

			assertThat(product.determineVersionType(candidate)).contains(ProductVersionType.PATCH);
		}

		@Test
		void coreOutputAndTitleBothChanged_prefersMajor() {
			// 복합 변경은 MAJOR 조건이 하나라도 있으면 전체를 MAJOR로 본다.
			Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent("제목", 1000, "본문 A"));
			ProductContent candidate = new ProductContent(
				ProductType.PROMPT, "새 제목", product.getDescription(), product.getModel(),
				product.getAmountType(), product.getAmount(), product.getThumbnailUrl(), product.getImageUrls(),
				"본문 B", product.getFileUrl(), product.getExternalUrl(), product.getTags());

			assertThat(product.determineVersionType(candidate)).contains(ProductVersionType.MAJOR);
		}

		@Test
		void nullTagsAndImages_areNormalizedToEmpty_soNoChangeIsNoOp() {
			Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent("제목", 1000, "본문"));
			ProductContent candidate = new ProductContent(
				ProductType.PROMPT, product.getName(), product.getDescription(), product.getModel(),
				product.getAmountType(), product.getAmount(), product.getThumbnailUrl(), null,
				product.getContent(), product.getFileUrl(), product.getExternalUrl(), null);

			assertThat(product.determineVersionType(candidate)).isEmpty();
		}

		@Test
		void tagOrderChanged_isPatch() {
			// 순서도 비교 대상이다 — 같은 태그라도 순서가 바뀌면 변경으로 본다.
			Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), new ProductContent(
				ProductType.PROMPT, "제목", "설명", "model", AmountType.PAID, 1000,
				null, List.of(), "본문", null, null, List.of("a", "b")));
			ProductContent candidate = new ProductContent(
				ProductType.PROMPT, product.getName(), product.getDescription(), product.getModel(),
				product.getAmountType(), product.getAmount(), product.getThumbnailUrl(), product.getImageUrls(),
				product.getContent(), product.getFileUrl(), product.getExternalUrl(), List.of("b", "a"));

			assertThat(product.determineVersionType(candidate)).contains(ProductVersionType.PATCH);
		}

		@Test
		void identicalContent_isNoOp() {
			Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent("제목", 1000, "본문"));

			assertThat(product.determineVersionType(candidateWithContent(product, product.getContent()))).isEmpty();
		}

		@Test
		void excelFileChanged_isMajor() {
			Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), excelContent());
			ProductContent candidate = new ProductContent(
				ProductType.EXCEL, product.getName(), product.getDescription(), product.getModel(),
				product.getAmountType(), product.getAmount(), product.getThumbnailUrl(), product.getImageUrls(),
				null, "products/1/file/b.xlsx", null, product.getTags());

			assertThat(product.determineVersionType(candidate)).contains(ProductVersionType.MAJOR);
		}

		@Test
		void paidToFree_isMajor() {
			Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent("제목", 1000, "본문"));
			ProductContent candidate = new ProductContent(
				ProductType.PROMPT, product.getName(), product.getDescription(), product.getModel(),
				AmountType.FREE, 0, product.getThumbnailUrl(), product.getImageUrls(),
				product.getContent(), product.getFileUrl(), product.getExternalUrl(), product.getTags());

			assertThat(product.determineVersionType(candidate)).contains(ProductVersionType.MAJOR);
		}

		@Test
		void descriptionOnlyChanged_isPatch() {
			Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent("제목", 1000, "본문"));

			assertThat(product.determineVersionType(new ProductContent(
				ProductType.PROMPT, product.getName(), "새 설명", product.getModel(),
				product.getAmountType(), product.getAmount(), product.getThumbnailUrl(), product.getImageUrls(),
				product.getContent(), product.getFileUrl(), product.getExternalUrl(), product.getTags())))
				.contains(ProductVersionType.PATCH);
		}

		@Test
		void modelOnlyChanged_isPatch() {
			Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent("제목", 1000, "본문"));

			assertThat(product.determineVersionType(new ProductContent(
				ProductType.PROMPT, product.getName(), product.getDescription(), "새 모델",
				product.getAmountType(), product.getAmount(), product.getThumbnailUrl(), product.getImageUrls(),
				product.getContent(), product.getFileUrl(), product.getExternalUrl(), product.getTags())))
				.contains(ProductVersionType.PATCH);
		}

		@Test
		void thumbnailOnlyChanged_isPatch() {
			Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), promptContent("제목", 1000, "본문"));

			assertThat(product.determineVersionType(new ProductContent(
				ProductType.PROMPT, product.getName(), product.getDescription(), product.getModel(),
				product.getAmountType(), product.getAmount(), "products/1/thumbnail/new.png", product.getImageUrls(),
				product.getContent(), product.getFileUrl(), product.getExternalUrl(), product.getTags())))
				.contains(ProductVersionType.PATCH);
		}

		@Test
		void imageValueChanged_isPatch() {
			// 순서가 아니라 값 자체가 바뀐 경우도 변경으로 본다.
			Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), new ProductContent(
				ProductType.PROMPT, "제목", "설명", "model", AmountType.PAID, 1000,
				null, List.of("products/1/image/a.png"), "본문", null, null, List.of()));

			assertThat(product.determineVersionType(new ProductContent(
				ProductType.PROMPT, product.getName(), product.getDescription(), product.getModel(),
				product.getAmountType(), product.getAmount(), product.getThumbnailUrl(), List.of("products/1/image/b.png"),
				product.getContent(), product.getFileUrl(), product.getExternalUrl(), product.getTags())))
				.contains(ProductVersionType.PATCH);
		}

		@Test
		void imageOrderChanged_isPatch() {
			Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), new ProductContent(
				ProductType.PROMPT, "제목", "설명", "model", AmountType.PAID, 1000,
				null, List.of("products/1/image/a.png", "products/1/image/b.png"), "본문", null, null, List.of()));

			assertThat(product.determineVersionType(new ProductContent(
				ProductType.PROMPT, product.getName(), product.getDescription(), product.getModel(),
				product.getAmountType(), product.getAmount(), product.getThumbnailUrl(),
				List.of("products/1/image/b.png", "products/1/image/a.png"),
				product.getContent(), product.getFileUrl(), product.getExternalUrl(), product.getTags())))
				.contains(ProductVersionType.PATCH);
		}

		@Test
		void tagValueChanged_isPatch() {
			// 순서가 아니라 값 자체가 바뀐 경우도 변경으로 본다.
			Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), new ProductContent(
				ProductType.PROMPT, "제목", "설명", "model", AmountType.PAID, 1000,
				null, List.of(), "본문", null, null, List.of("a", "b")));

			assertThat(product.determineVersionType(new ProductContent(
				ProductType.PROMPT, product.getName(), product.getDescription(), product.getModel(),
				product.getAmountType(), product.getAmount(), product.getThumbnailUrl(), product.getImageUrls(),
				product.getContent(), product.getFileUrl(), product.getExternalUrl(), List.of("a", "c"))))
				.contains(ProductVersionType.PATCH);
		}

		@Test
		void identicalNotionRequest_isNoOp() {
			Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), notionContent("제목", 1000));

			ProductContent candidate = new ProductContent(
				ProductType.NOTION, product.getName(), product.getDescription(), product.getModel(),
				product.getAmountType(), product.getAmount(), product.getThumbnailUrl(), product.getImageUrls(),
				null, null, product.getExternalUrl(), product.getTags());

			assertThat(product.determineVersionType(candidate)).isEmpty();
		}

		@Test
		void identicalPptRequest_isNoOp() {
			Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), pptContent());

			ProductContent candidate = new ProductContent(
				ProductType.PPT, product.getName(), product.getDescription(), product.getModel(),
				product.getAmountType(), product.getAmount(), product.getThumbnailUrl(), product.getImageUrls(),
				null, product.getFileUrl(), null, product.getTags());

			assertThat(product.determineVersionType(candidate)).isEmpty();
		}

		@Test
		void identicalExcelRequest_isNoOp() {
			Product product = Product.create(UUID.randomUUID(), UUID.randomUUID(), excelContent());

			ProductContent candidate = new ProductContent(
				ProductType.EXCEL, product.getName(), product.getDescription(), product.getModel(),
				product.getAmountType(), product.getAmount(), product.getThumbnailUrl(), product.getImageUrls(),
				null, product.getFileUrl(), null, product.getTags());

			assertThat(product.determineVersionType(candidate)).isEmpty();
		}

		private ProductContent excelContent() {
			return new ProductContent(
				ProductType.EXCEL, "제목", "설명", "model", AmountType.PAID, 1000,
				null, List.of(), null, "products/1/file/a.xlsx", null, List.of());
		}

		private ProductContent candidateWithContent(Product product, String content) {
			return new ProductContent(
				ProductType.PROMPT, product.getName(), product.getDescription(), product.getModel(),
				product.getAmountType(), product.getAmount(), product.getThumbnailUrl(), product.getImageUrls(),
				content, product.getFileUrl(), product.getExternalUrl(), product.getTags());
		}
	}
}
