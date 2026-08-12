package com.prompthub.product.presentation.dto.response.review;

import java.time.LocalDateTime;
import java.util.UUID;

public record ProductReviewResponse(
	UUID id,
	UUID userId,
	short rating,
	String content,
	LocalDateTime createdAt,
	LocalDateTime updatedAt
) {
}
