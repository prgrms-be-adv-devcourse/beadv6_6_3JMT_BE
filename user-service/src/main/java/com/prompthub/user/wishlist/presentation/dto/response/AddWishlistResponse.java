package com.prompthub.user.wishlist.presentation.dto.response;

import com.prompthub.user.wishlist.application.dto.AddWishlistResult;

import java.time.LocalDateTime;
import java.util.UUID;

public record AddWishlistResponse(
        UUID wishlistId,
        UUID productId,
        LocalDateTime createdAt
) {

    public static AddWishlistResponse from(AddWishlistResult result) {
        return new AddWishlistResponse(
                result.wishlistId(),
                result.productId(),
                result.createdAt()
        );
    }
}
