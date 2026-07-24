package com.prompthub.search.infra.es;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record ProductSearchDocument(
	UUID familyRootId,
	UUID productId,
	UUID sellerId,
	String name,
	String description,
	String content,
	List<String> tags,
	String productType,
	String model,
	int amount,
	String amountType,
	String thumbnailUrl,
	String badge,
	int salesCount,
	int viewCount,
	int reviewCount,
	double ratingAvg,
	LocalDateTime firstPublishedAt,
	LocalDateTime currentVersionAt
) {
}
