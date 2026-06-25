package com.prompthub.product.presentation.dto.request;

import java.util.UUID;

public record InternalReviewUpsertRequest(UUID buyerId, UUID productId, Integer rating) {
}
