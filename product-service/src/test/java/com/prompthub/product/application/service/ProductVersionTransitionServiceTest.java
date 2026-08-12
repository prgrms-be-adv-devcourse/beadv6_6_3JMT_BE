package com.prompthub.product.application.service;
import com.prompthub.product.application.service.inspection.ProductInspectionRequestPublisher;
import com.prompthub.product.application.service.seller.ProductVersionTransitionService;

import static com.prompthub.product.support.ProductContentFixtures.notionContent;
import static com.prompthub.product.support.ProductContentFixtures.promptContent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.prompthub.product.application.usecase.inspection.ProductEventPublisher;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.model.enums.ProductVersionType;
import com.prompthub.product.domain.repository.ProductRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** ON_SALE 상품의 새 버전 row 생성·저장·이벤트 발행을 검증한다(2026-08-05 로드맵 PR4). */
@ExtendWith(MockitoExtension.class)
class ProductVersionTransitionServiceTest {

	private static final UUID SELLER_ID = UUID.randomUUID();

	@Mock
	private ProductRepository productRepository;

	@Mock
	private ProductEventPublisher productEventPublisher;

	@Mock
	private ProductInspectionRequestPublisher productInspectionRequestPublisher;

	@InjectMocks
	private ProductVersionTransitionService versionTransition;

	@Test
	@DisplayName("PATCH는 기존 row를 SUPERSEDED로 교대시키고 PRODUCT_CHANGED를 발행한다")
	void transitionToNextVersion_patch_supersedesOldRowAndPublishesProductChanged() {
		Product onSale = onSale((short) 2, (short) 0);
		UUID nextId = UUID.randomUUID();

		Product next = versionTransition.transitionToNextVersion(
			onSale, nextId, ProductVersionType.PATCH, promptContent("새 제목", 2000), "가격 조정", onSale.getId());

		assertThat(onSale.getStatus()).isEqualTo(ProductStatus.SUPERSEDED);
		assertThat(next.getStatus()).isEqualTo(ProductStatus.ON_SALE);
		assertThat(next.getPatchVersion()).isEqualTo((short) 1);
		then(productRepository).should().save(onSale);
		then(productRepository).should().save(next);
		then(productEventPublisher).should().publishProductChanged(onSale.getId());
		then(productInspectionRequestPublisher).should(never()).publish(org.mockito.ArgumentMatchers.any());
	}

	@Test
	@DisplayName("MAJOR는 기존 row를 그대로 두고 검수 요청을 발행한다")
	void transitionToNextVersion_major_keepsOldRowAndPublishesInspectionRequest() {
		Product onSale = onSale((short) 2, (short) 0);
		UUID nextId = UUID.randomUUID();

		Product next = versionTransition.transitionToNextVersion(
			onSale, nextId, ProductVersionType.MAJOR, notionContent("새 제목", 2000), "본문 개정", onSale.getId());

		assertThat(onSale.getStatus()).isEqualTo(ProductStatus.ON_SALE);
		assertThat(next.getStatus()).isEqualTo(ProductStatus.PENDING_REVIEW);
		assertThat(next.getMajorVersion()).isEqualTo((short) 3);
		then(productRepository).should(never()).save(onSale);
		ArgumentCaptor<Product> savedCaptor = ArgumentCaptor.forClass(Product.class);
		then(productRepository).should().save(savedCaptor.capture());
		assertThat(savedCaptor.getValue()).isSameAs(next);
		then(productInspectionRequestPublisher).should().publish(next);
		then(productEventPublisher).should(never()).publishProductChanged(org.mockito.ArgumentMatchers.any());
	}

	private Product onSale(short majorVersion, short patchVersion) {
		Product product = Product.create(UUID.randomUUID(), SELLER_ID, promptContent());
		ReflectionTestUtils.setField(product, "status", ProductStatus.ON_SALE);
		ReflectionTestUtils.setField(product, "majorVersion", majorVersion);
		ReflectionTestUtils.setField(product, "patchVersion", patchVersion);
		return product;
	}
}
