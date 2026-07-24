package com.prompthub.search.application;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.entity.ProductFamily;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.repository.ProductRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * RDB의 ON_SALE 상품 전체와 ES 색인 전체를 비교해 다른 부분만 반영하는 전체 재조정.
 * 온디맨드 컨트롤러(/internal/search/reindex)와 주기 스케줄러(ProductReconcileScheduler)
 * 양쪽에서 호출한다. 실시간 이벤트가 없는 admin-service발 변화(승인/승인취소)의 안전망 —
 * product-service 자체 발 변화(생성/패치/판매중단)는 ProductSearchEventHandler가 실시간으로
 * 반영한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductReindexService {

	private final ProductRepository productRepository;
	private final ProductSearchIndexer productSearchIndexer;

	public void reconcileAll() {
		if (!productSearchIndexer.indexExists()) {
			log.info("ES 인덱스가 아직 없어 이번 재조정 사이클을 건너뜁니다.");
			return;
		}

		List<Product> onSaleProducts = productRepository.findAllByStatus(ProductStatus.ON_SALE);
		Map<UUID, List<Product>> byFamily = onSaleProducts.stream()
			.collect(Collectors.groupingBy(Product::familyRootId));

		List<FamilyUpsertInput> toUpsert = new ArrayList<>();
		for (UUID familyRootId : byFamily.keySet()) {
			List<Product> members = productRepository.findAllByFamilyRootIds(List.of(familyRootId));
			ProductFamily family = ProductFamily.of(familyRootId, members);
			family.currentOnSale().ifPresent(onSale -> {
				double averageRating = productRepository.getAverageRating(familyRootId);
				long familySalesCount = productRepository.sumSalesCountByFamilyRootId(familyRootId);
				long familyViewCount = productRepository.sumViewCountByFamilyRootId(familyRootId);
				LocalDateTime firstPublishedAt = members.stream()
					.map(Product::getCreatedAt)
					.min(Comparator.naturalOrder())
					.orElse(onSale.getCreatedAt());
				toUpsert.add(new FamilyUpsertInput(onSale, familySalesCount, familyViewCount, averageRating, firstPublishedAt));
			});
		}

		Set<UUID> onSaleFamilyRootIds = byFamily.keySet();
		Set<UUID> indexedFamilyRootIds = productSearchIndexer.findAllIndexedFamilyRootIds();
		List<UUID> toDelete = indexedFamilyRootIds.stream()
			.filter(id -> !onSaleFamilyRootIds.contains(id))
			.toList();

		productSearchIndexer.bulkReconcile(toUpsert, toDelete);
		log.info("전체 재조정 완료. upsert={}, delete={}", toUpsert.size(), toDelete.size());
	}
}
