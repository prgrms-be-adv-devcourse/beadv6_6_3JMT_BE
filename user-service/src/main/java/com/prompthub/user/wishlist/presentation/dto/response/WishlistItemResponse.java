package com.prompthub.user.wishlist.presentation.dto.response;

import com.prompthub.user.wishlist.application.dto.WishlistItemResult;

import java.time.LocalDateTime;
import java.util.UUID;

public record WishlistItemResponse(
        UUID wishlistId,
        UUID productId,
        String title,
        String thumbnailUrl,
        long price,
        String sellerNickname,
        double averageRating,
        long salesCount,
        String category,
        String model,
        LocalDateTime addedAt
) {

    public static WishlistItemResponse from(WishlistItemResult result) {
        return new WishlistItemResponse(
                result.wishlistId(),
                result.productId(),
                result.title(),
                result.thumbnailUrl(),
                result.price(),
                result.sellerNickname(),
                result.averageRating(),
                result.salesCount(),
                result.category(),
                result.model(),
                result.addedAt()
        );
    }
}
