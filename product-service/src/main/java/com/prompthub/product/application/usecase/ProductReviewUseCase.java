package com.prompthub.product.application.usecase;

import java.util.UUID;

public interface ProductReviewUseCase {

	void upsertReview(UUID buyerId, UUID productId, Integer rating);
}
