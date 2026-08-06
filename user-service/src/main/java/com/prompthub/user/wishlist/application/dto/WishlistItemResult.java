package com.prompthub.user.wishlist.application.dto;

import com.prompthub.user.wishlist.domain.model.Wishlist;
import java.time.LocalDateTime;
import java.util.UUID;

public record WishlistItemResult(
        UUID wishlistId,
        UUID productId,
        LocalDateTime addedAt
) {

    public static WishlistItemResult from(Wishlist wishlist) {
        return new WishlistItemResult(
                wishlist.getWishlistId(),
                wishlist.getProductId(),
                wishlist.getCreatedAt()
        );
    }
}
