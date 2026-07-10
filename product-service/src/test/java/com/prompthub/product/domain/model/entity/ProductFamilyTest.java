package com.prompthub.product.domain.model.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.product.domain.model.enums.AmountType;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.model.enums.ProductType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ProductFamilyTest {

	@Test
	void currentOnSale_returnsTheOnlyOnSaleRow() {
		Product root = product(ProductStatus.SUPERSEDED, (short) 1, (short) 0);
		Product child = product(ProductStatus.ON_SALE, (short) 2, (short) 0);
		ProductFamily family = ProductFamily.of(root.getId(), List.of(root, child));

		assertThat(family.currentOnSale()).contains(child);
	}

	@Test
	void currentForWishlist_prefersOnSaleOverStopped() {
		Product stopped = product(ProductStatus.STOPPED, (short) 1, (short) 0);
		Product onSale = product(ProductStatus.ON_SALE, (short) 2, (short) 0);
		ProductFamily family = ProductFamily.of(stopped.getId(), List.of(stopped, onSale));

		assertThat(family.currentForWishlist()).contains(onSale);
	}

	@Test
	void currentForWishlist_neverReturnsSuperseded() {
		Product superseded = product(ProductStatus.SUPERSEDED, (short) 1, (short) 0);
		ProductFamily family = ProductFamily.of(superseded.getId(), List.of(superseded));

		assertThat(family.currentForWishlist()).isEmpty();
	}

	@Test
	void currentForSeller_prefersPendingReviewOverOnSale() {
		Product onSale = product(ProductStatus.ON_SALE, (short) 2, (short) 0);
		Product pending = product(ProductStatus.PENDING_REVIEW, (short) 3, (short) 0);
		ProductFamily family = ProductFamily.of(onSale.getId(), List.of(onSale, pending));

		assertThat(family.currentForSeller()).contains(pending);
	}

	@Test
	void hasEverBeenOnSale_falseWhenOnlyDraftOrPendingOrRejected() {
		Product draft = product(ProductStatus.DRAFT, (short) 1, (short) 0);
		ProductFamily family = ProductFamily.of(draft.getId(), List.of(draft));

		assertThat(family.hasEverBeenOnSale()).isFalse();
	}

	@Test
	void hasEverBeenOnSale_trueWhenSupersededExists() {
		Product superseded = product(ProductStatus.SUPERSEDED, (short) 1, (short) 0);
		Product onSale = product(ProductStatus.ON_SALE, (short) 2, (short) 0);
		ProductFamily family = ProductFamily.of(superseded.getId(), List.of(superseded, onSale));

		assertThat(family.hasEverBeenOnSale()).isTrue();
	}

	@Test
	void publicHistory_excludesRejectedAndPendingReview_sortedDescending() {
		Product superseded = product(ProductStatus.SUPERSEDED, (short) 1, (short) 0);
		Product rejected = product(ProductStatus.REJECTED, (short) 2, (short) 0);
		Product onSale = product(ProductStatus.ON_SALE, (short) 3, (short) 0);
		ProductFamily family = ProductFamily.of(superseded.getId(), List.of(superseded, rejected, onSale));

		assertThat(family.publicHistory()).containsExactly(onSale, superseded);
	}

	@Test
	void mostRecentSuperseded_returnsHighestVersionSuperseded() {
		Product old = product(ProductStatus.SUPERSEDED, (short) 1, (short) 0);
		Product recent = product(ProductStatus.SUPERSEDED, (short) 2, (short) 0);
		Product onSale = product(ProductStatus.ON_SALE, (short) 3, (short) 0);
		ProductFamily family = ProductFamily.of(old.getId(), List.of(old, recent, onSale));

		assertThat(family.mostRecentSuperseded()).contains(recent);
	}

	private Product product(ProductStatus status, short majorVersion, short patchVersion) {
		Product product = Product.create(
			UUID.randomUUID(), UUID.randomUUID(), ProductType.PROMPT,
			"제목", "설명", "model", AmountType.PAID, 1000,
			null, List.of(), "content", List.of()
		);
		ReflectionTestUtils.setField(product, "status", status);
		ReflectionTestUtils.setField(product, "majorVersion", majorVersion);
		ReflectionTestUtils.setField(product, "patchVersion", patchVersion);
		return product;
	}
}
