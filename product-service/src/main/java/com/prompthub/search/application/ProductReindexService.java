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
}
