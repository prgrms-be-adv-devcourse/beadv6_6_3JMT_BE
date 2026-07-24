package com.prompthub.search.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.repository.ProductRepository;
import com.prompthub.product.support.ProductContentFixtures;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ProductReindexServiceTest {

	@Mock
	private ProductRepository productRepository;

	@Mock
	private ProductSearchIndexer productSearchIndexer;

	private ProductReindexService reindexService;

	@BeforeEach
	void setUp() {
		reindexService = new ProductReindexService(productRepository, productSearchIndexer);
	}

	@Test
	@SuppressWarnings("unchecked")
	void reconcileAll_ON_SALE_family는_upsert_대상에_담는다() {
		UUID familyRootId = UUID.randomUUID();
		Product onSale = product(familyRootId, ProductStatus.ON_SALE);
		given(productRepository.findAllByStatus(ProductStatus.ON_SALE)).willReturn(List.of(onSale));
		given(productRepository.findAllByFamilyRootIds(List.of(familyRootId))).willReturn(List.of(onSale));
		given(productRepository.getAverageRating(familyRootId)).willReturn(4.0);
		given(productRepository.sumSalesCountByFamilyRootId(familyRootId)).willReturn(5L);
		given(productSearchIndexer.findAllIndexedFamilyRootIds()).willReturn(Set.of(familyRootId));

		reindexService.reconcileAll();

		ArgumentCaptor<List<FamilyUpsertInput>> upsertCaptor = ArgumentCaptor.forClass(List.class);
		ArgumentCaptor<List<UUID>> deleteCaptor = ArgumentCaptor.forClass(List.class);
		verify(productSearchIndexer).bulkReconcile(upsertCaptor.capture(), deleteCaptor.capture());
		assertThat(upsertCaptor.getValue()).anySatisfy(input -> {
			assertThat(input.onSale().familyRootId()).isEqualTo(familyRootId);
			assertThat(input.familySalesCount()).isEqualTo(5L);
			assertThat(input.averageRating()).isEqualTo(4.0);
		});
		assertThat(deleteCaptor.getValue()).isEmpty();
	}

	@Test
	@SuppressWarnings("unchecked")
	void reconcileAll_ES에만_있고_더_이상_ON_SALE_아닌_family는_삭제_대상에_담는다() {
		UUID staleFamilyRootId = UUID.randomUUID();
		given(productRepository.findAllByStatus(ProductStatus.ON_SALE)).willReturn(List.of());
		given(productSearchIndexer.findAllIndexedFamilyRootIds()).willReturn(Set.of(staleFamilyRootId));

		reindexService.reconcileAll();

		ArgumentCaptor<List<UUID>> deleteCaptor = ArgumentCaptor.forClass(List.class);
		verify(productSearchIndexer).bulkReconcile(any(), deleteCaptor.capture());
		assertThat(deleteCaptor.getValue()).containsExactly(staleFamilyRootId);
	}

	private Product product(UUID id, ProductStatus status) {
		Product product = Product.create(id, UUID.randomUUID(), ProductContentFixtures.promptContent());
		ReflectionTestUtils.setField(product, "status", status);
		return product;
	}
}
