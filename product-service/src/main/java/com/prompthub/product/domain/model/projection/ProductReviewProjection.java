package com.prompthub.product.domain.model.projection;

import java.time.LocalDateTime;
import java.util.UUID;

public record ProductReviewProjection(
	UUID id,
	UUID userId,
	short rating,
	String content,
	LocalDateTime createdAt,
	LocalDateTime updatedAt
) {
}
