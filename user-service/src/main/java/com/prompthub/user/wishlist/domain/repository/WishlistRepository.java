package com.prompthub.user.wishlist.domain.repository;

import com.prompthub.user.wishlist.domain.model.Wishlist;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WishlistRepository {

    Wishlist save(Wishlist wishlist);

    Optional<Wishlist> findById(UUID wishlistId);

    Optional<Wishlist> findByUserIdAndProductId(UUID userId, UUID productId);

    List<Wishlist> findByUserId(UUID userId, int page, int size);

    long countByUserId(UUID userId);

    boolean existsByUserIdAndProductId(UUID userId, UUID productId);

    void delete(Wishlist wishlist);
}
