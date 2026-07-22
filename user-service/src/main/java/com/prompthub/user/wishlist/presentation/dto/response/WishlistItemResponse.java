package com.prompthub.user.wishlist.presentation.dto.response;

import com.prompthub.user.wishlist.application.dto.WishlistItemResult;

import java.time.LocalDateTime;
import java.util.UUID;

public record WishlistItemResponse(
        UUID wishlistId,
        UUID productId,
        LocalDateTime addedAt
) {

    public static WishlistItemResponse from(WishlistItemResult result) {
        return new WishlistItemResponse(
                result.wishlistId(),
                result.productId(),
                result.addedAt()
        );
    }
}
