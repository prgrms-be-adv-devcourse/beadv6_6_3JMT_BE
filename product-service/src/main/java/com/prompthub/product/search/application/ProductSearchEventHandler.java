package com.prompthub.product.search.application;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.entity.ProductFamily;
import com.prompthub.product.domain.model.entity.ProductProcessedEvent;
import com.prompthub.product.domain.repository.ProcessedEventRepository;
import com.prompthub.product.domain.repository.ProductRepository;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * product-events 소비의 멱등성(eventId+consumerGroup="product-service-search")과
 * ES 색인 반영을 한 트랜잭션으로 묶는다. (kafka-event.md §7, es-1 §4)
 */
@Service
@RequiredArgsConstructor
public class ProductSearchEventHandler {

	private static final String CONSUMER_GROUP = "product-service-search";

	private final ProductRepository productRepository;
	private final ProcessedEventRepository processedEventRepository;
	private final ProductSearchIndexer productSearchIndexer;

	@Transactional
	public void handleOnSaleChanged(UUID eventId, LocalDateTime occurredAt, UUID familyRootId) {
		if (alreadyProcessed(eventId)) {
			return;
		}
		reconcileFamily(familyRootId);
		markProcessed(eventId, "PRODUCT_ON_SALE_CHANGED", occurredAt);
	}

	@Transactional
	public void handleStoppedOrDeleted(UUID eventId, LocalDateTime occurredAt, UUID productId, String eventType) {
		if (alreadyProcessed(eventId)) {
			return;
		}
		UUID familyRootId = productRepository.findById(productId).map(Product::familyRootId).orElse(productId);
		reconcileFamily(familyRootId);
		markProcessed(eventId, eventType, occurredAt);
	}

	@Transactional
	public void handlePriceChanged(UUID eventId, LocalDateTime occurredAt, UUID productId, int changedPrice) {
		if (alreadyProcessed(eventId)) {
			return;
		}
		UUID familyRootId = productRepository.findById(productId).map(Product::familyRootId).orElse(productId);
		productSearchIndexer.updatePrice(familyRootId, changedPrice);
		markProcessed(eventId, "PRODUCT_PRICE_CHANGED", occurredAt);
	}

	private void reconcileFamily(UUID familyRootId) {
		List<Product> members = productRepository.findAllByFamilyRootIds(List.of(familyRootId));
		ProductFamily family = ProductFamily.of(familyRootId, members);
		family.currentOnSale().ifPresentOrElse(
			onSale -> {
				double averageRating = productRepository.getAverageRating(familyRootId);
				long familySalesCount = productRepository.sumSalesCountByFamilyRootId(familyRootId);
				LocalDateTime firstPublishedAt = members.stream()
					.map(Product::getCreatedAt)
					.min(Comparator.naturalOrder())
					.orElse(onSale.getCreatedAt());
				productSearchIndexer.upsert(onSale, familySalesCount, averageRating, firstPublishedAt);
			},
			() -> productSearchIndexer.delete(familyRootId)
		);
	}

	private boolean alreadyProcessed(UUID eventId) {
		return processedEventRepository.existsByEventIdAndConsumerGroup(eventId, CONSUMER_GROUP);
	}

	private void markProcessed(UUID eventId, String eventType, LocalDateTime occurredAt) {
		processedEventRepository.save(ProductProcessedEvent.create(eventId, CONSUMER_GROUP, eventType, occurredAt));
	}
}
