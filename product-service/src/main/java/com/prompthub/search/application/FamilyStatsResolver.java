package com.prompthub.search.application;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.repository.ProductRepository;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * family 단위 검색 색인 통계(family 합산 salesCount/viewCount, 평균 평점, 최초 게시일)를
 * 계산해 {@link FamilyUpsertInput}으로 묶는다. 실시간 경로(ProductSearchEventHandler)와
 * 배치 경로(ProductReindexService) 양쪽이 각자 family를 순회하며 같은 계산을 반복하던 것을
 * 여기 하나로 모았다.
 */
@Component
@RequiredArgsConstructor
public class FamilyStatsResolver {

	private final ProductRepository productRepository;

	public FamilyUpsertInput resolve(UUID familyRootId, List<Product> members, Product representative) {
		double averageRating = productRepository.getAverageRating(familyRootId);
		long familySalesCount = productRepository.sumSalesCountByFamilyRootId(familyRootId);
		long familyViewCount = productRepository.sumViewCountByFamilyRootId(familyRootId);
		LocalDateTime firstPublishedAt = members.stream()
			.map(Product::getCreatedAt)
			.min(Comparator.naturalOrder())
			.orElse(representative.getCreatedAt());
		return new FamilyUpsertInput(representative, familySalesCount, familyViewCount, averageRating, firstPublishedAt);
	}
}
