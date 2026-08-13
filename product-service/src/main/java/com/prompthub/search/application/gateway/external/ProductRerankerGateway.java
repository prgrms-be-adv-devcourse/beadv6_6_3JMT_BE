package com.prompthub.search.application.gateway.external;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductRerankerGateway {

	Optional<List<UUID>> findRelevantProductIds(String keyword, List<RerankCandidate> candidates);

	record RerankCandidate(
		UUID productId,
		String name,
		List<String> tags,
		String description,
		String productType,
		String model
	) {
	}
}
