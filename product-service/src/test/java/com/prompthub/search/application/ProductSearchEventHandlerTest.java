package com.prompthub.search.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.repository.ProcessedEventRepository;
import com.prompthub.product.domain.repository.ProductRepository;
import com.prompthub.product.support.ProductContentFixtures;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ProductSearchEventHandlerTest {

	private static final UUID FAMILY_ROOT_ID = UUID.randomUUID();
	private static final UUID EVENT_ID = UUID.randomUUID();

	@Mock
	private ProductRepository productRepository;

	@Mock
	private ProcessedEventRepository processedEventRepository;

	@Mock
	private ProductSearchIndexer productSearchIndexer;

	private ProductSearchEventHandler handler;

	@BeforeEach
	void setUp() {
		handler = new ProductSearchEventHandler(productRepository, processedEventRepository, productSearchIndexer);
	}

	@Test
	void handleProductChanged_ON_SALE_멤버가_있으면_그걸_대표로_upsert한다() {
		Product onSale = product(FAMILY_ROOT_ID, ProductStatus.ON_SALE);
		given(processedEventRepository.existsByEventIdAndConsumerGroup(EVENT_ID, "product-service-search")).willReturn(false);
		given(productRepository.findAllByFamilyRootIds(List.of(FAMILY_ROOT_ID))).willReturn(List.of(onSale));
		given(productRepository.getAverageRating(FAMILY_ROOT_ID)).willReturn(4.5);
		given(productRepository.sumSalesCountByFamilyRootId(FAMILY_ROOT_ID)).willReturn(10L);
		given(productRepository.sumViewCountByFamilyRootId(FAMILY_ROOT_ID)).willReturn(3L);

		handler.handleProductChanged(EVENT_ID, LocalDateTime.now(), FAMILY_ROOT_ID);

		verify(productSearchIndexer).upsert(onSale, 10L, 3L, 4.5, onSale.getCreatedAt());
	}

	@Test
	void handleProductChanged_ON_SALE_멤버가_없으면_색인에서_삭제한다() {
		Product draft = product(FAMILY_ROOT_ID, ProductStatus.DRAFT);
		given(processedEventRepository.existsByEventIdAndConsumerGroup(EVENT_ID, "product-service-search")).willReturn(false);
		given(productRepository.findAllByFamilyRootIds(List.of(FAMILY_ROOT_ID))).willReturn(List.of(draft));

		handler.handleProductChanged(EVENT_ID, LocalDateTime.now(), FAMILY_ROOT_ID);

		verify(productSearchIndexer).bulkReconcile(List.of(), List.of(FAMILY_ROOT_ID));
		verify(productSearchIndexer, never()).upsert(any(), anyLong(), anyLong(), anyDouble(), any());
	}

	@Test
	void handleProductChanged_이미_처리한_eventId면_아무것도_하지_않는다() {
		given(processedEventRepository.existsByEventIdAndConsumerGroup(EVENT_ID, "product-service-search")).willReturn(true);

		handler.handleProductChanged(EVENT_ID, LocalDateTime.now(), FAMILY_ROOT_ID);

		verify(productRepository, never()).findAllByFamilyRootIds(any());
	}

	@Test
	void handleProductRemovalCandidate_productId로_familyRootId를_찾아_재조정한다() {
		UUID stoppedProductId = UUID.randomUUID();
		Product stillOnSale = product(FAMILY_ROOT_ID, ProductStatus.ON_SALE);
		Product stopped = product(FAMILY_ROOT_ID, ProductStatus.STOPPED);
		given(processedEventRepository.existsByEventIdAndConsumerGroup(EVENT_ID, "product-service-search")).willReturn(false);
		given(productRepository.findById(stoppedProductId)).willReturn(Optional.of(stopped));
		given(productRepository.findAllByFamilyRootIds(List.of(FAMILY_ROOT_ID))).willReturn(List.of(stillOnSale, stopped));
		given(productRepository.getAverageRating(FAMILY_ROOT_ID)).willReturn(4.0);
		given(productRepository.sumSalesCountByFamilyRootId(FAMILY_ROOT_ID)).willReturn(1L);
		given(productRepository.sumViewCountByFamilyRootId(FAMILY_ROOT_ID)).willReturn(2L);

		handler.handleProductRemovalCandidate(EVENT_ID, LocalDateTime.now(), "PRODUCT_STOPPED", stoppedProductId);

		verify(productSearchIndexer).upsert(stillOnSale, 1L, 2L, 4.0, stillOnSale.getCreatedAt());
	}

	@Test
	void handleProductRemovalCandidate_ON_SALE_멤버가_없으면_삭제한다() {
		UUID stoppedProductId = UUID.randomUUID();
		Product stopped = product(FAMILY_ROOT_ID, ProductStatus.STOPPED);
		given(processedEventRepository.existsByEventIdAndConsumerGroup(EVENT_ID, "product-service-search")).willReturn(false);
		given(productRepository.findById(stoppedProductId)).willReturn(Optional.of(stopped));
		given(productRepository.findAllByFamilyRootIds(List.of(FAMILY_ROOT_ID))).willReturn(List.of(stopped));

		handler.handleProductRemovalCandidate(EVENT_ID, LocalDateTime.now(), "PRODUCT_STOPPED", stoppedProductId);

		verify(productSearchIndexer).bulkReconcile(List.of(), List.of(FAMILY_ROOT_ID));
	}

	@Test
	void handleProductRemovalCandidate_productId를_찾을_수_없으면_아무것도_하지_않는다() {
		UUID unknownProductId = UUID.randomUUID();
		given(processedEventRepository.existsByEventIdAndConsumerGroup(EVENT_ID, "product-service-search")).willReturn(false);
		given(productRepository.findById(unknownProductId)).willReturn(Optional.empty());

		handler.handleProductRemovalCandidate(EVENT_ID, LocalDateTime.now(), "PRODUCT_DELETED", unknownProductId);

		verifyNoInteractions(productSearchIndexer);
	}

	@Test
	void handleProductRemovalCandidate_이미_처리한_eventId면_아무것도_하지_않는다() {
		given(processedEventRepository.existsByEventIdAndConsumerGroup(EVENT_ID, "product-service-search")).willReturn(true);

		handler.handleProductRemovalCandidate(EVENT_ID, LocalDateTime.now(), "PRODUCT_STOPPED", UUID.randomUUID());

		verify(productRepository, never()).findById(any());
	}

	private Product product(UUID id, ProductStatus status) {
		Product product = Product.create(id, UUID.randomUUID(), ProductContentFixtures.promptContent());
		ReflectionTestUtils.setField(product, "status", status);
		return product;
	}
}
