package com.prompthub.search.application.indexing;

import com.prompthub.product.domain.model.entity.Product;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * family 단위 검색 색인 통계(family 합산 salesCount/viewCount, 최초 게시일)를 계산해
 * {@link FamilyUpsertInput}으로 묶는다. {@link ProductReindexService}의 증분·전체 재조정
 * 양쪽이 같은 계산을 하므로 여기 하나로 모았다.
 *
 * <p>합산은 호출자가 이미 조회한 {@code members}에서 메모리로 계산한다 — family마다
 * 집계 쿼리를 던지면 N+1이 되기 때문이다. 평균 평점은 Review 테이블 집계라 멤버 목록으로
 * 계산할 수 없으므로 호출자가 {@code getAverageRatings}로 family 단위 일괄 조회해 넘긴다.
 */
@Component
public class FamilyStatsResolver {

	public FamilyUpsertInput buildFamilyUpsertInput(
		List<Product> members, Product representative, double averageRating, float[] embedding
	) {
		List<Product> alive = members.stream()
			.filter(member -> member.getDeletedAt() == null)
			.toList();

		long familySalesCount = alive.stream().mapToLong(Product::getSalesCount).sum();
		long familyViewCount = alive.stream().mapToLong(Product::getViewCount).sum();
		LocalDateTime firstPublishedAt = alive.stream()
			.map(Product::getCreatedAt)
			.min(Comparator.naturalOrder())
			.orElse(representative.getCreatedAt());

		return new FamilyUpsertInput(representative, familySalesCount, familyViewCount, averageRating, firstPublishedAt, embedding);
	}
}
