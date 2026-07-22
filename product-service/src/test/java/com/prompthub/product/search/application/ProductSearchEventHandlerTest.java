package com.prompthub.product.search.application;

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
	void handleOnSaleChanged_ON_SALE_멤버가_있으면_upsert한다() {
		Product onSale = product(FAMILY_ROOT_ID, ProductStatus.ON_SALE);
		given(processedEventRepository.existsByEventIdAndConsumerGroup(EVENT_ID, "product-service-search")).willReturn(false);
		given(productRepository.findAllByFamilyRootIds(List.of(FAMILY_ROOT_ID))).willReturn(List.of(onSale));
		given(productRepository.getAverageRating(FAMILY_ROOT_ID)).willReturn(4.5);
		given(productRepository.sumSalesCountByFamilyRootId(FAMILY_ROOT_ID)).willReturn(10L);

		handler.handleOnSaleChanged(EVENT_ID, LocalDateTime.now(), FAMILY_ROOT_ID);

		verify(productSearchIndexer).upsert(any(), org.mockito.ArgumentMatchers.eq(10L), org.mockito.ArgumentMatchers.eq(4.5), any());
		verify(productSearchIndexer, never()).delete(any());
	}

	@Test
	void handleOnSaleChanged_ON_SALE_멤버가_없으면_delete한다() {
		given(processedEventRepository.existsByEventIdAndConsumerGroup(EVENT_ID, "product-service-search")).willReturn(false);
		given(productRepository.findAllByFamilyRootIds(List.of(FAMILY_ROOT_ID))).willReturn(List.of());

		handler.handleOnSaleChanged(EVENT_ID, LocalDateTime.now(), FAMILY_ROOT_ID);

		verify(productSearchIndexer).delete(FAMILY_ROOT_ID);
		verify(productSearchIndexer, never()).upsert(any(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyDouble(), any());
	}

	@Test
	void handleOnSaleChanged_이미_처리한_eventId면_아무것도_하지_않는다() {
		given(processedEventRepository.existsByEventIdAndConsumerGroup(EVENT_ID, "product-service-search")).willReturn(true);

		handler.handleOnSaleChanged(EVENT_ID, LocalDateTime.now(), FAMILY_ROOT_ID);

		verify(productRepository, never()).findAllByFamilyRootIds(any());
	}

	@Test
	void handlePriceChanged_familyRootId를_찾아서_가격만_갱신한다() {
		UUID productId = UUID.randomUUID();
		Product product = product(FAMILY_ROOT_ID, ProductStatus.ON_SALE);
		given(processedEventRepository.existsByEventIdAndConsumerGroup(EVENT_ID, "product-service-search")).willReturn(false);
		given(productRepository.findById(productId)).willReturn(Optional.of(product));

		handler.handlePriceChanged(EVENT_ID, LocalDateTime.now(), productId, 9900);

		verify(productSearchIndexer).updatePrice(FAMILY_ROOT_ID, 9900);
	}

	private Product product(UUID id, ProductStatus status) {
		Product product = Product.create(id, UUID.randomUUID(), ProductContentFixtures.promptContent());
		ReflectionTestUtils.setField(product, "status", status);
		return product;
	}
}
