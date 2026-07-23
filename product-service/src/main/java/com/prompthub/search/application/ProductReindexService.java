package com.prompthub.search.application;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.entity.ProductFamily;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.repository.ProductRepository;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * ES 초기 적재·매핑 변경 시 전체 재생성용 온디맨드 배치. 자동 스케줄은 없다
 * (아웃박스로 실시간 경로 신뢰성이 확보돼 유실 복구 목적의 자동 배치는 불필요 —
 * admin-onsale-event-design.md §5).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductReindexService {

	private final ProductRepository productRepository;
	private final ProductSearchIndexer productSearchIndexer;

	public void reindexAll() {
		List<Product> onSaleProducts = productRepository.findAllByStatus(ProductStatus.ON_SALE);
		Map<UUID, List<Product>> byFamily = onSaleProducts.stream()
			.collect(Collectors.groupingBy(Product::familyRootId));

		for (UUID familyRootId : byFamily.keySet()) {
			List<Product> members = productRepository.findAllByFamilyRootIds(List.of(familyRootId));
			ProductFamily family = ProductFamily.of(familyRootId, members);
			family.currentOnSale().ifPresent(onSale -> {
				double averageRating = productRepository.getAverageRating(familyRootId);
				long familySalesCount = productRepository.sumSalesCountByFamilyRootId(familyRootId);
				LocalDateTime firstPublishedAt = members.stream()
					.map(Product::getCreatedAt)
					.min(Comparator.naturalOrder())
					.orElse(onSale.getCreatedAt());
				productSearchIndexer.upsert(onSale, familySalesCount, averageRating, firstPublishedAt);
			});
		}
		log.info("풀 리인덱스 완료. family={}", byFamily.size());
	}

	/**
	 * 10분 주기 카운트 동기화 — 지난 주기 이후 Product 또는 Review가 바뀐 family만
	 * 골라 부분 갱신한다(전체 재색인 대신). 실패 시 재시도는 하지 않는다 —
	 * ElasticsearchProductSearchIndexer.updateCounts 참고.
	 */
	public void syncChangedCounts(LocalDateTime since) {
		List<UUID> changedFamilyRootIds = productRepository.findChangedFamilyRootIds(since);

		for (UUID familyRootId : changedFamilyRootIds) {
			List<Product> members = productRepository.findAllByFamilyRootIds(List.of(familyRootId));
			ProductFamily family = ProductFamily.of(familyRootId, members);
			family.currentOnSale().ifPresent(onSale -> {
				double averageRating = productRepository.getAverageRating(familyRootId);
				long familySalesCount = productRepository.sumSalesCountByFamilyRootId(familyRootId);
				productSearchIndexer.updateCounts(familyRootId, familySalesCount, onSale.getViewCount(), averageRating);
			});
		}
		log.info("변경분 카운트 동기화 완료. family={}", changedFamilyRootIds.size());
	}
}
