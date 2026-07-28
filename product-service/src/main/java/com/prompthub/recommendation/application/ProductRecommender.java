package com.prompthub.recommendation.application;

import com.prompthub.product.domain.model.projection.SimilarProductProjection;
import com.prompthub.product.domain.repository.ProductRepository;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 상세 페이지에 보여줄 "비슷한 상품"을 고른다.
 *
 * <p>임베딩이 가까운 후보를 넉넉히 받아 유형 가산점을 얹어 다시 세운 뒤 상위 몇 개를 고른다.
 * 표시용 데이터는 여기서 채우지 않는다 — 고른 id를 호출자가 기존 상품 조회로 채운다.
 */
@Component
@RequiredArgsConstructor
public class ProductRecommender {

	/**
	 * 같은 유형에 주는 가산점.
	 *
	 * <p>코사인 유사도가 0~1로 유계라 이 값은 <b>"다른 유형이 같은 유형을 앞서려면 유사도가
	 * 얼마나 더 높아야 하는가"</b>로 직접 읽힌다 — 0.05면 유사도 0.90인 PPT가 0.84인 PROMPT를
	 * 앞선다.
	 *
	 * <p>설정으로 빼지 않는다. {@code configs/}가 config server 이미지에 구워져 yml을 고쳐도
	 * 머지·재배포가 필요하고(config/CLAUDE.md "커밋 ≠ 반영"), 오히려 배포 단계만 는다.
	 * 조정 신호인 추천 클릭률(#381)이 생겨 반복 조정이 필요해지면 그때 외부화한다.
	 */
	private static final double TYPE_BONUS = 0.05;

	/**
	 * 요청 개수의 몇 배를 후보로 받을지.
	 *
	 * <p>요청한 만큼만 가져오면 같은 유형이 그 자리를 다 채웠을 때 다른 유형이 후보에도 들지
	 * 못해, 가산점이 순서를 뒤집을 여지가 사라진다. 고정 상수 대신 요청에 비례시킨다.
	 */
	static final int CANDIDATE_MULTIPLIER = 5;

	private final ProductRepository productRepository;

	/**
	 * @param baseProductType 기준 상품의 유형. 이 유형과 같은 후보가 가산점을 받는다
	 * @return 추천 순서대로 정렬된 상품 id. 최대 {@code limit}개
	 */
	public List<UUID> recommend(UUID productId, UUID familyRootId, String baseProductType, int limit) {
		List<SimilarProductProjection> candidates =
			productRepository.findSimilarProducts(productId, familyRootId, limit * CANDIDATE_MULTIPLIER);

		return candidates.stream()
			.sorted(Comparator.comparingDouble((SimilarProductProjection c) -> score(c, baseProductType)).reversed())
			.limit(limit)
			.map(SimilarProductProjection::id)
			.toList();
	}

	/**
	 * {@code <=>}가 코사인 <b>거리</b>라 유사도로 뒤집어 쓴다.
	 *
	 * <p>유형을 하드 필터나 1차 정렬 키로 두지 않는다 — FE가 4개만 요청하므로 같은 유형 후보가
	 * 4개만 있어도 다른 유형이 절대 노출되지 않는다. 내용이 정말 비슷하면 유형이 달라도 올라와야
	 * 한다.
	 *
	 * <p>인기도 항({@code log1p(salesCount)×w})은 넣지 않는다. salesCount는 스케일이 무계라
	 * 가중치의 근거도 해석도 없다 — 코사인 공간에서 해석되는 항 하나만 둔다.
	 */
	private double score(SimilarProductProjection candidate, String baseProductType) {
		double similarity = 1 - candidate.distance();
		return candidate.productType().equals(baseProductType) ? similarity + TYPE_BONUS : similarity;
	}
}
