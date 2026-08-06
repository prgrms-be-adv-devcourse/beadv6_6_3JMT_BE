package com.prompthub.search.application;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record ProductSearchHit(
	UUID productId,
	UUID sellerId,
	String name,
	String description,
	String productType,
	String model,
	int amount,
	String thumbnailUrl,
	List<String> tags,
	int salesCount,
	double ratingAvg,
	LocalDateTime firstPublishedAt,
	LocalDateTime currentVersionAt
) {
}
