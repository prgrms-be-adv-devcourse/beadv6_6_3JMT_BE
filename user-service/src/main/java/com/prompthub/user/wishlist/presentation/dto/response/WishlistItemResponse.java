package com.prompthub.user.wishlist.presentation.dto.response;

import com.prompthub.user.wishlist.application.dto.WishlistItemResult;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Wishlist 항목 응답")
public record WishlistItemResponse(
        @Schema(description = "Wishlist ID(UUID)", example = "3f1b1b0e-5e3a-4c7f-8d9a-1b2c3d4e5f60")
        UUID wishlistId,
        @Schema(description = "상품 ID(UUID)", example = "4a2c2c1f-6f4b-5d8a-9e0b-2c3d4e5f6071")
        UUID productId,
        @Schema(description = "Wishlist 등록 일시", example = "2026-07-22T12:00:00")
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
