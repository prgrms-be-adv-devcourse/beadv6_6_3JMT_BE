package com.prompthub.search.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.repository.ProductRepository;
import com.prompthub.product.support.ProductContentFixtures;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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

	@Mock
	private FamilyStatsResolver familyStatsResolver;

	@Mock
	private ProductEmbeddingUpdater productEmbeddingUpdater;

	private ProductReindexService reindexService;

	@BeforeEach
	void setUp() {
		reindexService = new ProductReindexService(
			productRepository, productSearchIndexer, familyStatsResolver, productEmbeddingUpdater);
	}

	@Test
	@SuppressWarnings("unchecked")
	void reconcileAll_ON_SALE_family는_upsert_대상에_담는다() {
		UUID familyRootId = UUID.randomUUID();
		Product onSale = product(familyRootId, ProductStatus.ON_SALE);
		FamilyUpsertInput expectedInput = new FamilyUpsertInput(onSale, 5L, 9L, 4.0, onSale.getCreatedAt(), null);
		given(productSearchIndexer.indexExists()).willReturn(true);
		given(productRepository.findAllByStatus(ProductStatus.ON_SALE)).willReturn(List.of(onSale));
		given(productRepository.findAllByFamilyRootIds(List.of(familyRootId))).willReturn(List.of(onSale));
		given(productRepository.getAverageRatings(List.of(familyRootId))).willReturn(Map.of(familyRootId, 4.0));
		given(familyStatsResolver.resolve(List.of(onSale), onSale, 4.0, null)).willReturn(expectedInput);
		given(productSearchIndexer.findAllIndexedFamilyRootIds()).willReturn(Set.of(familyRootId));

		reindexService.reconcileAll();

		ArgumentCaptor<List<FamilyUpsertInput>> upsertCaptor = ArgumentCaptor.forClass(List.class);
		ArgumentCaptor<List<UUID>> deleteCaptor = ArgumentCaptor.forClass(List.class);
		verify(productSearchIndexer).bulkReconcile(upsertCaptor.capture(), deleteCaptor.capture());
		assertThat(upsertCaptor.getValue()).containsExactly(expectedInput);
		assertThat(deleteCaptor.getValue()).isEmpty();
	}

	@Test
	@SuppressWarnings("unchecked")
	void reconcileAll_ES에만_있고_더_이상_ON_SALE_아닌_family는_삭제_대상에_담는다() {
		UUID staleFamilyRootId = UUID.randomUUID();
		given(productSearchIndexer.indexExists()).willReturn(true);
		given(productRepository.findAllByStatus(ProductStatus.ON_SALE)).willReturn(List.of());
		given(productSearchIndexer.findAllIndexedFamilyRootIds()).willReturn(Set.of(staleFamilyRootId));

		reindexService.reconcileAll();

		ArgumentCaptor<List<UUID>> deleteCaptor = ArgumentCaptor.forClass(List.class);
		verify(productSearchIndexer).bulkReconcile(any(), deleteCaptor.capture());
		assertThat(deleteCaptor.getValue()).containsExactly(staleFamilyRootId);
	}

	@Test
	void reconcileAll_family_수와_무관하게_조회는_상수_건이다() {
		UUID firstRootId = UUID.randomUUID();
		UUID secondRootId = UUID.randomUUID();
		Product first = product(firstRootId, ProductStatus.ON_SALE);
		Product second = product(secondRootId, ProductStatus.ON_SALE);
		given(productSearchIndexer.indexExists()).willReturn(true);
		given(productRepository.findAllByStatus(ProductStatus.ON_SALE)).willReturn(List.of(first, second));
		given(productRepository.findAllByFamilyRootIds(anyList())).willReturn(List.of(first, second));
		given(productRepository.getAverageRatings(anyList()))
			.willReturn(Map.of(firstRootId, 4.0, secondRootId, 3.0));
		given(familyStatsResolver.resolve(anyList(), any(), anyDouble(), any()))
			.willReturn(new FamilyUpsertInput(first, 0L, 0L, 0.0, first.getCreatedAt(), null));
		given(productSearchIndexer.findAllIndexedFamilyRootIds()).willReturn(Set.of());

		reindexService.reconcileAll();

		// family가 2개여도 멤버·평점 조회는 각각 1회씩 — family당 반복 조회(N+1)가 없어야 한다
		verify(productRepository).findAllByFamilyRootIds(anyList());
		verify(productRepository).getAverageRatings(anyList());
	}

	@Test
	void reconcileAll_인덱스가_아직_없으면_아무것도_하지_않고_건너뛴다() {
		given(productSearchIndexer.indexExists()).willReturn(false);

		boolean succeeded = reindexService.reconcileAll();

		assertThat(succeeded).isFalse();
		verify(productRepository, never()).findAllByStatus(any());
		verify(productSearchIndexer, never()).bulkReconcile(any(), any());
	}

	@Test
	void reconcileChanged_변경이_없으면_ES에_아무것도_쓰지_않는다() {
		LocalDateTime since = LocalDateTime.now().minusSeconds(20);
		given(productSearchIndexer.indexExists()).willReturn(true);
		given(productRepository.findChangedFamilyRootIds(since)).willReturn(List.of());

		boolean succeeded = reindexService.reconcileChanged(since);

		assertThat(succeeded).isTrue();
		verify(productRepository, never()).findAllByFamilyRootIds(any());
		verify(productSearchIndexer, never()).bulkReconcile(any(), any());
	}

	@Test
	@SuppressWarnings("unchecked")
	void reconcileChanged_변경된_family만_upsert한다() {
		LocalDateTime since = LocalDateTime.now().minusSeconds(20);
		UUID changedRootId = UUID.randomUUID();
		Product onSale = product(changedRootId, ProductStatus.ON_SALE);
		FamilyUpsertInput expectedInput = new FamilyUpsertInput(onSale, 3L, 7L, 5.0, onSale.getCreatedAt(), null);
		given(productSearchIndexer.indexExists()).willReturn(true);
		given(productRepository.findChangedFamilyRootIds(since)).willReturn(List.of(changedRootId));
		given(productRepository.findAllByFamilyRootIds(List.of(changedRootId))).willReturn(List.of(onSale));
		given(productRepository.getAverageRatings(List.of(changedRootId))).willReturn(Map.of(changedRootId, 5.0));
		given(familyStatsResolver.resolve(List.of(onSale), onSale, 5.0, null)).willReturn(expectedInput);

		reindexService.reconcileChanged(since);

		ArgumentCaptor<List<FamilyUpsertInput>> upsertCaptor = ArgumentCaptor.forClass(List.class);
		ArgumentCaptor<List<UUID>> deleteCaptor = ArgumentCaptor.forClass(List.class);
		verify(productSearchIndexer).bulkReconcile(upsertCaptor.capture(), deleteCaptor.capture());
		assertThat(upsertCaptor.getValue()).containsExactly(expectedInput);
		assertThat(deleteCaptor.getValue()).isEmpty();
		// 증분 경로는 ES 전체 문서 목록을 훑지 않는다
		verify(productSearchIndexer, never()).findAllIndexedFamilyRootIds();
	}

	@Test
	@SuppressWarnings("unchecked")
	void reconcileChanged_ON_SALE이_사라진_family는_삭제_대상에_담는다() {
		LocalDateTime since = LocalDateTime.now().minusSeconds(20);
		UUID stoppedRootId = UUID.randomUUID();
		Product stopped = product(stoppedRootId, ProductStatus.STOPPED);
		given(productSearchIndexer.indexExists()).willReturn(true);
		given(productRepository.findChangedFamilyRootIds(since)).willReturn(List.of(stoppedRootId));
		given(productRepository.findAllByFamilyRootIds(List.of(stoppedRootId))).willReturn(List.of(stopped));
		given(productRepository.getAverageRatings(List.of(stoppedRootId))).willReturn(Map.of());

		reindexService.reconcileChanged(since);

		ArgumentCaptor<List<UUID>> deleteCaptor = ArgumentCaptor.forClass(List.class);
		verify(productSearchIndexer).bulkReconcile(any(), deleteCaptor.capture());
		assertThat(deleteCaptor.getValue()).containsExactly(stoppedRootId);
	}

	@Test
	void reconcileChanged_인덱스가_아직_없으면_건너뛰고_실패를_알린다() {
		given(productSearchIndexer.indexExists()).willReturn(false);

		boolean succeeded = reindexService.reconcileChanged(LocalDateTime.now().minusSeconds(20));

		assertThat(succeeded).isFalse();
		verify(productRepository, never()).findChangedFamilyRootIds(any());
		verify(productSearchIndexer, never()).bulkReconcile(any(), any());
	}

	private Product product(UUID id, ProductStatus status) {
		Product product = Product.create(id, UUID.randomUUID(), ProductContentFixtures.promptContent());
		ReflectionTestUtils.setField(product, "status", status);
		return product;
	}
}
