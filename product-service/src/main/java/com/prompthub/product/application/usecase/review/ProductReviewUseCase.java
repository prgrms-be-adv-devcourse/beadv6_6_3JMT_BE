package com.prompthub.product.application.usecase.review;

import java.util.UUID;

public interface ProductReviewUseCase {

	void upsertReview(UUID buyerId, UUID productId, Integer rating);
}
