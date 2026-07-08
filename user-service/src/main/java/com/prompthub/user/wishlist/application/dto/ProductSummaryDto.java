package com.prompthub.user.wishlist.application.dto;

import java.util.UUID;

public record ProductSummaryDto(
        UUID productId,
        UUID sellerId,
        String title,
        long price,
        String thumbnailUrl,
        String model,
        long salesCount,
        double averageRating,
        String status
) {
}
