package com.prompthub.user.wishlist.application.dto;

import com.prompthub.user.wishlist.domain.model.Wishlist;

import java.time.LocalDateTime;
import java.util.UUID;

public record AddWishlistResult(
        UUID wishlistId,
        UUID productId,
        LocalDateTime createdAt
) {

    public static AddWishlistResult from(Wishlist wishlist) {
        return new AddWishlistResult(
                wishlist.getWishlistId(),
                wishlist.getProductId(),
                wishlist.getCreatedAt()
        );
    }
}
