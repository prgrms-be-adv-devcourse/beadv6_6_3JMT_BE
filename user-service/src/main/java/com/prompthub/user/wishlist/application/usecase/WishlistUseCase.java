package com.prompthub.user.wishlist.application.usecase;

import com.prompthub.user.wishlist.application.dto.AddWishlistCommand;
import com.prompthub.user.wishlist.application.dto.AddWishlistResult;
import com.prompthub.user.wishlist.application.dto.WishlistItemResult;

import java.util.List;
import java.util.UUID;

public interface WishlistUseCase {

    AddWishlistResult add(AddWishlistCommand command);

    void remove(UUID wishlistId, UUID userId);

    List<WishlistItemResult> getWishlists(UUID userId, int page, int size);

    long countWishlists(UUID userId);

    boolean exists(UUID userId, UUID productId);
}
