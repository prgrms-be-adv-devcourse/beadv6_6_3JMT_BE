package com.prompthub.product.application.service;
import com.prompthub.product.application.service.seller.ProductVersionChangePolicy;

import static com.prompthub.product.support.ProductContentFixtures.notionContent;
import static com.prompthub.product.support.ProductContentFixtures.promptContent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.entity.ProductFamily;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.model.enums.ProductVersionType;
import com.prompthub.product.exception.ProductException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** ON_SALE 수정 요청이 실제로 새 버전을 만들 자격이 있는지 판정하는 로직을 검증한다(2026-08-05 로드맵 PR4). */
class ProductVersionChangePolicyTest {

	private static final UUID SELLER_ID = UUID.randomUUID();

	private final ProductVersionChangePolicy policy = new ProductVersionChangePolicy();

	@Test
	@DisplayName("변경 내용이 없으면 no-op(empty)을 돌려준다")
	void decideVersionChange_noRealChange_returnsEmpty() {
		Product onSale = onSale();
		ProductFamily family = ProductFamily.of(onSale.getId(), List.of(onSale));

		Optional<ProductVersionType> result = policy.decideVersionChange(onSale, promptContent(), family, null);

		assertThat(result).isEmpty();
	}

	@Test
	@DisplayName("핵심 산출물이 바뀌었는데 changeReason이 없으면 거부한다")
	void decideVersionChange_realChangeWithoutChangeReason_throws() {
		Product onSale = onSale();
		ProductFamily family = ProductFamily.of(onSale.getId(), List.of(onSale));

		assertThatThrownBy(() -> policy.decideVersionChange(onSale, notionContent("새 제목", 2000), family, " "))
			.isInstanceOf(ProductException.class);
	}

	@Test
	@DisplayName("이미 PENDING_REVIEW인 MAJOR 변경이 family에 있으면 새 MAJOR 제출을 거부한다")
	void decideVersionChange_majorWhilePendingReviewExists_throws() {
		Product onSale = onSale();
		Product pending = onSale(); // 별도 row로 흉내
		ReflectionTestUtils.setField(pending, "status", ProductStatus.PENDING_REVIEW);
		ReflectionTestUtils.setField(pending, "parentId", onSale.getId());
		ProductFamily family = ProductFamily.of(onSale.getId(), List.of(onSale, pending));

		assertThatThrownBy(() -> policy.decideVersionChange(onSale, notionContent("새 제목", 2000), family, "본문 개정"))
			.isInstanceOf(ProductException.class);
	}

	@Test
	@DisplayName("정상적인 MAJOR 변경은 changeReason만 있으면 MAJOR를 돌려준다")
	void decideVersionChange_validMajorChange_returnsMajor() {
		Product onSale = onSale();
		ProductFamily family = ProductFamily.of(onSale.getId(), List.of(onSale));

		Optional<ProductVersionType> result = policy.decideVersionChange(onSale, notionContent("새 제목", 2000), family, "본문 개정");

		assertThat(result).contains(ProductVersionType.MAJOR);
	}

	@Test
	@DisplayName("metadata만 바뀐 PATCH 변경은 family에 대기 중인 MAJOR가 있어도 허용한다")
	void decideVersionChange_patchWhilePendingReviewExists_returnsPatch() {
		Product onSale = onSale();
		Product pending = onSale();
		ReflectionTestUtils.setField(pending, "status", ProductStatus.PENDING_REVIEW);
		ReflectionTestUtils.setField(pending, "parentId", onSale.getId());
		ProductFamily family = ProductFamily.of(onSale.getId(), List.of(onSale, pending));

		Optional<ProductVersionType> result =
			policy.decideVersionChange(onSale, promptContent("새 제목", 2000), family, "가격 조정");

		assertThat(result).contains(ProductVersionType.PATCH);
	}

	private Product onSale() {
		Product product = Product.create(UUID.randomUUID(), SELLER_ID, promptContent());
		ReflectionTestUtils.setField(product, "status", ProductStatus.ON_SALE);
		return product;
	}
}
