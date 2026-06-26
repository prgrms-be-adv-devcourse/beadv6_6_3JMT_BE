package com.prompthub.user.wishlist.application.dto;

import java.util.UUID;

public record AddWishlistCommand(
        UUID userId,
        UUID productId
) {
}
