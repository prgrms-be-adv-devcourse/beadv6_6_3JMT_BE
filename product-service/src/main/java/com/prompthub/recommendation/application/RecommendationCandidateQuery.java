package com.prompthub.recommendation.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 추천 정책이 ES 후보 수집과 Jina 관련성 검증을 요청하는 아웃바운드 포트. */
public interface RecommendationCandidateQuery {

	List<UUID> findRelevantProductIds(List<Seed> seeds, Set<UUID> excludedFamilyRootIds, int limit);

	record Seed(
		UUID productId,
		String text,
		String rerankText,
		float[] embedding,
		double weight,
		Signal signal
	) {
	}

	enum Signal {
		SIMILAR_PRODUCT,
		CART,
		PURCHASE
	}
}
