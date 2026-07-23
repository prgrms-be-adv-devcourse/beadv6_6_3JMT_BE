package com.prompthub.search.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.repository.ProductRepository;
import com.prompthub.product.support.ProductContentFixtures;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
	void reindexAll_ON_SALE_family마다_upsert를_호출한다() {
		UUID familyRootId = UUID.randomUUID();
		Product onSale = product(familyRootId, ProductStatus.ON_SALE);
		given(productRepository.findAllByStatus(ProductStatus.ON_SALE)).willReturn(List.of(onSale));
		given(productRepository.findAllByFamilyRootIds(List.of(familyRootId))).willReturn(List.of(onSale));
		given(productRepository.getAverageRating(familyRootId)).willReturn(4.0);
		given(productRepository.sumSalesCountByFamilyRootId(familyRootId)).willReturn(5L);

		reindexService.reindexAll();

		verify(productSearchIndexer).upsert(any(), org.mockito.ArgumentMatchers.eq(5L), org.mockito.ArgumentMatchers.eq(4.0), any());
	}

	@Test
	void syncChangedCounts_바뀐_family만_부분갱신한다() {
		UUID familyRootId = UUID.randomUUID();
		LocalDateTime since = LocalDateTime.now().minusMinutes(10);
		Product onSale = product(familyRootId, ProductStatus.ON_SALE);
		given(productRepository.findChangedFamilyRootIds(since)).willReturn(List.of(familyRootId));
		given(productRepository.findAllByFamilyRootIds(List.of(familyRootId))).willReturn(List.of(onSale));
		given(productRepository.getAverageRating(familyRootId)).willReturn(4.5);
		given(productRepository.sumSalesCountByFamilyRootId(familyRootId)).willReturn(7L);

		reindexService.syncChangedCounts(since);

		verify(productSearchIndexer).updateCounts(familyRootId, 7L, onSale.getViewCount(), 4.5);
		verify(productSearchIndexer, org.mockito.Mockito.never()).upsert(any(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyDouble(), any());
	}

	@Test
	void syncChangedCounts_ON_SALE가_아니면_건드리지_않는다() {
		UUID familyRootId = UUID.randomUUID();
		LocalDateTime since = LocalDateTime.now().minusMinutes(10);
		Product superseded = product(familyRootId, ProductStatus.SUPERSEDED);
		given(productRepository.findChangedFamilyRootIds(since)).willReturn(List.of(familyRootId));
		given(productRepository.findAllByFamilyRootIds(List.of(familyRootId))).willReturn(List.of(superseded));

		reindexService.syncChangedCounts(since);

		verify(productSearchIndexer, org.mockito.Mockito.never())
			.updateCounts(any(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyDouble());
	}

	private Product product(UUID id, ProductStatus status) {
		Product product = Product.create(id, UUID.randomUUID(), ProductContentFixtures.promptContent());
		ReflectionTestUtils.setField(product, "status", status);
		return product;
	}
}
