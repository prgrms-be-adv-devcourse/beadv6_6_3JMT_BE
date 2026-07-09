package com.prompthub.user.wishlist.application.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record WishlistItemResult(
        UUID wishlistId,
        UUID productId,
        String title,
        String thumbnailUrl,
        long price,
        String sellerNickname,
        double averageRating,
        long salesCount,
        String model,
        LocalDateTime addedAt
) {
}
