package com.prompthub.product.search.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.repository.ProductRepository;
import com.prompthub.product.support.ProductContentFixtures;
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

	private Product product(UUID id, ProductStatus status) {
		Product product = Product.create(id, UUID.randomUUID(), ProductContentFixtures.promptContent());
		ReflectionTestUtils.setField(product, "status", status);
		return product;
	}
}
