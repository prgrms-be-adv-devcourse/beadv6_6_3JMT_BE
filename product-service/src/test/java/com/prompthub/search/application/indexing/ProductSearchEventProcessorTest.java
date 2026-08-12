package com.prompthub.search.application.indexing;

import static org.mockito.ArgumentMatchers.any;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ProductSearchEventProcessorTest {

	private static final UUID FAMILY_ROOT_ID = UUID.randomUUID();
	private static final UUID EVENT_ID = UUID.randomUUID();

	@Mock
	private ProductRepository productRepository;

	@Mock
	private ProcessedEventRepository processedEventRepository;

	@Mock
	private ProductSearchIndexPort productSearchIndexer;

	@Mock
	private FamilyStatsResolver familyStatsResolver;

	private ProductSearchEventProcessor handler;

	@BeforeEach
	void setUp() {
		handler = new ProductSearchEventProcessor(productRepository, processedEventRepository, productSearchIndexer, familyStatsResolver);
	}

	@Test
	void handleProductChanged_ON_SALE_멤버가_있으면_그걸_대표로_upsert한다() {
		Product onSale = product(FAMILY_ROOT_ID, ProductStatus.ON_SALE);
		FamilyUpsertInput expectedInput = new FamilyUpsertInput(onSale, 10L, 3L, 4.5, onSale.getCreatedAt(), null);
		given(processedEventRepository.existsByEventIdAndConsumerGroup(EVENT_ID, "product-service-search")).willReturn(false);
		given(productRepository.findAllByFamilyRootIds(List.of(FAMILY_ROOT_ID))).willReturn(List.of(onSale));
		given(productRepository.getAverageRating(FAMILY_ROOT_ID)).willReturn(4.5);
		given(familyStatsResolver.buildFamilyUpsertInput(List.of(onSale), onSale, 4.5, null)).willReturn(expectedInput);

		handler.processProductChanged(EVENT_ID, LocalDateTime.now(), FAMILY_ROOT_ID);

		verify(productSearchIndexer).upsert(expectedInput);
	}

	@Test
	void handleProductChanged_저장된_embedding이_있으면_대표_상품_id로_읽어_실어보낸다() {
		Product onSale = product(FAMILY_ROOT_ID, ProductStatus.ON_SALE);
		float[] embedding = new float[] {0.1f, 0.2f};
		FamilyUpsertInput expectedInput = new FamilyUpsertInput(onSale, 10L, 3L, 4.5, onSale.getCreatedAt(), embedding);
		given(processedEventRepository.existsByEventIdAndConsumerGroup(EVENT_ID, "product-service-search")).willReturn(false);
		given(productRepository.findAllByFamilyRootIds(List.of(FAMILY_ROOT_ID))).willReturn(List.of(onSale));
		given(productRepository.getAverageRating(FAMILY_ROOT_ID)).willReturn(4.5);
		given(productRepository.findEmbeddings(List.of(onSale.getId()))).willReturn(Map.of(onSale.getId(), embedding));
		given(familyStatsResolver.buildFamilyUpsertInput(List.of(onSale), onSale, 4.5, embedding)).willReturn(expectedInput);

		handler.processProductChanged(EVENT_ID, LocalDateTime.now(), FAMILY_ROOT_ID);

		verify(productSearchIndexer).upsert(expectedInput);
	}

	@Test
	void handleProductChanged_ON_SALE_멤버가_없으면_색인에서_삭제한다() {
		Product draft = product(FAMILY_ROOT_ID, ProductStatus.DRAFT);
		given(processedEventRepository.existsByEventIdAndConsumerGroup(EVENT_ID, "product-service-search")).willReturn(false);
		given(productRepository.findAllByFamilyRootIds(List.of(FAMILY_ROOT_ID))).willReturn(List.of(draft));

		handler.processProductChanged(EVENT_ID, LocalDateTime.now(), FAMILY_ROOT_ID);

		verify(productSearchIndexer).bulkReconcile(List.of(), List.of(FAMILY_ROOT_ID));
		verify(productSearchIndexer, never()).upsert(any());
	}

	@Test
	void handleProductChanged_이미_처리한_eventId면_아무것도_하지_않는다() {
		given(processedEventRepository.existsByEventIdAndConsumerGroup(EVENT_ID, "product-service-search")).willReturn(true);

		handler.processProductChanged(EVENT_ID, LocalDateTime.now(), FAMILY_ROOT_ID);

		verify(productRepository, never()).findAllByFamilyRootIds(any());
	}

	@Test
	void handleProductRemovalCandidate_productId로_familyRootId를_찾아_재조정한다() {
		UUID stoppedProductId = UUID.randomUUID();
		Product stillOnSale = product(FAMILY_ROOT_ID, ProductStatus.ON_SALE);
		Product stopped = product(FAMILY_ROOT_ID, ProductStatus.STOPPED);
		FamilyUpsertInput expectedInput = new FamilyUpsertInput(stillOnSale, 1L, 2L, 4.0, stillOnSale.getCreatedAt(), null);
		given(processedEventRepository.existsByEventIdAndConsumerGroup(EVENT_ID, "product-service-search")).willReturn(false);
		given(productRepository.findById(stoppedProductId)).willReturn(Optional.of(stopped));
		given(productRepository.findAllByFamilyRootIds(List.of(FAMILY_ROOT_ID))).willReturn(List.of(stillOnSale, stopped));
		given(productRepository.getAverageRating(FAMILY_ROOT_ID)).willReturn(4.0);
		given(familyStatsResolver.buildFamilyUpsertInput(List.of(stillOnSale, stopped), stillOnSale, 4.0, null)).willReturn(expectedInput);

		handler.processRemovalCandidate(EVENT_ID, LocalDateTime.now(), "PRODUCT_STOPPED", stoppedProductId);

		verify(productSearchIndexer).upsert(expectedInput);
	}

	@Test
	void handleProductRemovalCandidate_ON_SALE_멤버가_없으면_삭제한다() {
		UUID stoppedProductId = UUID.randomUUID();
		Product stopped = product(FAMILY_ROOT_ID, ProductStatus.STOPPED);
		given(processedEventRepository.existsByEventIdAndConsumerGroup(EVENT_ID, "product-service-search")).willReturn(false);
		given(productRepository.findById(stoppedProductId)).willReturn(Optional.of(stopped));
		given(productRepository.findAllByFamilyRootIds(List.of(FAMILY_ROOT_ID))).willReturn(List.of(stopped));

		handler.processRemovalCandidate(EVENT_ID, LocalDateTime.now(), "PRODUCT_STOPPED", stoppedProductId);

		verify(productSearchIndexer).bulkReconcile(List.of(), List.of(FAMILY_ROOT_ID));
	}

	@Test
	void handleProductRemovalCandidate_productId를_찾을_수_없으면_아무것도_하지_않는다() {
		UUID unknownProductId = UUID.randomUUID();
		given(processedEventRepository.existsByEventIdAndConsumerGroup(EVENT_ID, "product-service-search")).willReturn(false);
		given(productRepository.findById(unknownProductId)).willReturn(Optional.empty());

		handler.processRemovalCandidate(EVENT_ID, LocalDateTime.now(), "PRODUCT_DELETED", unknownProductId);

		verifyNoInteractions(productSearchIndexer);
	}

	@Test
	void handleProductRemovalCandidate_이미_처리한_eventId면_아무것도_하지_않는다() {
		given(processedEventRepository.existsByEventIdAndConsumerGroup(EVENT_ID, "product-service-search")).willReturn(true);

		handler.processRemovalCandidate(EVENT_ID, LocalDateTime.now(), "PRODUCT_STOPPED", UUID.randomUUID());

		verify(productRepository, never()).findById(any());
	}

	private Product product(UUID id, ProductStatus status) {
		Product product = Product.create(id, UUID.randomUUID(), ProductContentFixtures.promptContent());
		ReflectionTestUtils.setField(product, "status", status);
		return product;
	}
}
