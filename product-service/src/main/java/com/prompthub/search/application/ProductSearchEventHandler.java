package com.prompthub.search.application;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.entity.ProductFamily;
import com.prompthub.product.domain.model.entity.ProductProcessedEvent;
import com.prompthub.product.domain.repository.ProcessedEventRepository;
import com.prompthub.product.domain.repository.ProductRepository;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * product-events 소비의 멱등성(eventId+consumerGroup="product-service-search")과
 * ES 색인 반영을 한 트랜잭션으로 묶는다. 대상 family에 현재 ON_SALE 멤버가 있으면 upsert,
 * 없으면 색인에서 제거한다 — product-service 자체가 아는 변화(생성·패치·판매중단)는 이 경로로
 * 실시간 반영되고, admin-service발 변화(승인취소)만 트리거할 이벤트가 없어 주기적 전체 재조정
 * 배치(ProductReindexService)의 몫으로 남는다.
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
		markProcessed(eventId, occurredAt, "PRODUCT_CHANGED");
	}

	@Transactional
	public void handleProductRemovalCandidate(UUID eventId, LocalDateTime occurredAt, String eventType, UUID productId) {
		if (alreadyProcessed(eventId)) {
			return;
		}
		productRepository.findById(productId)
			.map(Product::familyRootId)
			.ifPresent(this::reconcileFamily);
		markProcessed(eventId, occurredAt, eventType);
	}

	private void reconcileFamily(UUID familyRootId) {
		List<Product> members = productRepository.findAllByFamilyRootIds(List.of(familyRootId));
		ProductFamily family = ProductFamily.of(familyRootId, members);
		Optional<Product> currentOnSale = family.currentOnSale();
		if (currentOnSale.isEmpty()) {
			productSearchIndexer.bulkReconcile(List.of(), List.of(familyRootId));
			return;
		}

		Product representative = currentOnSale.get();
		double averageRating = productRepository.getAverageRating(familyRootId);
		long familySalesCount = productRepository.sumSalesCountByFamilyRootId(familyRootId);
		long familyViewCount = productRepository.sumViewCountByFamilyRootId(familyRootId);
		LocalDateTime firstPublishedAt = members.stream()
			.map(Product::getCreatedAt)
			.min(Comparator.naturalOrder())
			.orElse(representative.getCreatedAt());
		productSearchIndexer.upsert(representative, familySalesCount, familyViewCount, averageRating, firstPublishedAt);
	}

	private boolean alreadyProcessed(UUID eventId) {
		return processedEventRepository.existsByEventIdAndConsumerGroup(eventId, CONSUMER_GROUP);
	}

	private void markProcessed(UUID eventId, LocalDateTime occurredAt, String eventType) {
		processedEventRepository.save(ProductProcessedEvent.create(eventId, CONSUMER_GROUP, eventType, occurredAt));
	}
}
