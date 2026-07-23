package com.prompthub.search.application;

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
 * ES 색인 반영을 한 트랜잭션으로 묶는다. PRODUCT_CHANGED는 create·패치버전 갱신에서만
 * 발행되므로(결과가 항상 upsert 대상), 재조회 후 무조건 upsert한다 — 삭제 판단은
 * 7일 주기 전체 재조정 배치(ProductReindexService)의 몫이다.
 */
@Service
@RequiredArgsConstructor
public class ProductSearchEventHandler {

	private static final String CONSUMER_GROUP = "product-service-search";

	private final ProductRepository productRepository;
	private final ProcessedEventRepository processedEventRepository;
	private final ProductSearchIndexer productSearchIndexer;

	@Transactional
	public void handleProductChanged(UUID eventId, LocalDateTime occurredAt, UUID familyRootId) {
		if (alreadyProcessed(eventId)) {
			return;
		}
		reconcileFamily(familyRootId);
		markProcessed(eventId, occurredAt);
	}

	private void reconcileFamily(UUID familyRootId) {
		List<Product> members = productRepository.findAllByFamilyRootIds(List.of(familyRootId));
		ProductFamily family = ProductFamily.of(familyRootId, members);
		Product representative = family.currentOnSale().orElseGet(() -> family.sellerHistory().get(0));
		double averageRating = productRepository.getAverageRating(familyRootId);
		long familySalesCount = productRepository.sumSalesCountByFamilyRootId(familyRootId);
		LocalDateTime firstPublishedAt = members.stream()
			.map(Product::getCreatedAt)
			.min(Comparator.naturalOrder())
			.orElse(representative.getCreatedAt());
		productSearchIndexer.upsert(representative, familySalesCount, averageRating, firstPublishedAt);
	}

	private boolean alreadyProcessed(UUID eventId) {
		return processedEventRepository.existsByEventIdAndConsumerGroup(eventId, CONSUMER_GROUP);
	}

	private void markProcessed(UUID eventId, LocalDateTime occurredAt) {
		processedEventRepository.save(ProductProcessedEvent.create(eventId, CONSUMER_GROUP, "PRODUCT_CHANGED", occurredAt));
	}
}
