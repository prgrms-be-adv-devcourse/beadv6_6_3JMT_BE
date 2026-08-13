package com.prompthub.search.application.gateway.external;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductRerankerGateway {

	Optional<List<RankedProduct>> findRelevantProducts(String keyword, List<RerankCandidate> candidates);

	default Optional<List<UUID>> findRelevantProductIds(String keyword, List<RerankCandidate> candidates) {
		return findRelevantProducts(keyword, candidates)
			.map(products -> products.stream().map(RankedProduct::productId).toList());
	}

	record RankedProduct(UUID productId, double relevanceScore) {
	}

	record RerankCandidate(
		UUID productId,
		String name,
		List<String> tags,
		String description,
		String productType,
		String model,
		boolean includeModel
	) {
	}
}
