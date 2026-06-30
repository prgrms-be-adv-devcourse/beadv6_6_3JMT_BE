package com.prompthub.user.wishlist.presentation.dto.request;

import com.prompthub.user.wishlist.application.dto.AddWishlistCommand;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AddWishlistRequest(
        @NotNull UUID productId
) {

    public AddWishlistCommand toCommand(UUID userId) {
        return new AddWishlistCommand(userId, productId);
    }
}
