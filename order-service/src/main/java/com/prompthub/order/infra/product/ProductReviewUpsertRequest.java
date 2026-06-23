package com.prompthub.order.infra.product;

import java.util.UUID;

public record ProductReviewUpsertRequest(
        UUID buyerId,
        UUID productId,
        Integer rating
) {
}
