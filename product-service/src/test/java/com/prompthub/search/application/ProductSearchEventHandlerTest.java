package com.prompthub.search.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.repository.ProcessedEventRepository;
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

		handler.handleProductChanged(EVENT_ID, LocalDateTime.now(), FAMILY_ROOT_ID);

		verify(productSearchIndexer).upsert(onSale, 10L, 4.5, onSale.getCreatedAt());
	}

	@Test
	void handleProductChanged_ON_SALE_멤버가_없어도_최신_버전을_대표로_upsert한다() {
		Product draft = product(FAMILY_ROOT_ID, ProductStatus.DRAFT);
		given(processedEventRepository.existsByEventIdAndConsumerGroup(EVENT_ID, "product-service-search")).willReturn(false);
		given(productRepository.findAllByFamilyRootIds(List.of(FAMILY_ROOT_ID))).willReturn(List.of(draft));
		given(productRepository.getAverageRating(FAMILY_ROOT_ID)).willReturn(0.0);
		given(productRepository.sumSalesCountByFamilyRootId(FAMILY_ROOT_ID)).willReturn(0L);

		handler.handleProductChanged(EVENT_ID, LocalDateTime.now(), FAMILY_ROOT_ID);

		verify(productSearchIndexer).upsert(draft, 0L, 0.0, draft.getCreatedAt());
	}

	@Test
	void handleProductChanged_이미_처리한_eventId면_아무것도_하지_않는다() {
		given(processedEventRepository.existsByEventIdAndConsumerGroup(EVENT_ID, "product-service-search")).willReturn(true);

		handler.handleProductChanged(EVENT_ID, LocalDateTime.now(), FAMILY_ROOT_ID);

		verify(productRepository, never()).findAllByFamilyRootIds(any());
	}

	private Product product(UUID id, ProductStatus status) {
		Product product = Product.create(id, UUID.randomUUID(), ProductContentFixtures.promptContent());
		ReflectionTestUtils.setField(product, "status", status);
		return product;
	}
}
