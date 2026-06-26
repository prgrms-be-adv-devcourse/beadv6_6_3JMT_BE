package com.prompthub.user.wishlist.infrastructure.persistence;

import com.prompthub.user.wishlist.domain.model.Wishlist;
import com.prompthub.user.wishlist.domain.repository.WishlistRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class WishlistRepositoryAdapter implements WishlistRepository {

    private final WishlistJpaRepository wishlistJpaRepository;

    @Override
    public Wishlist save(Wishlist wishlist) {
        return wishlistJpaRepository.save(wishlist);
    }

    @Override
    public Optional<Wishlist> findById(UUID wishlistId) {
        return wishlistJpaRepository.findById(wishlistId);
    }

    @Override
    public Optional<Wishlist> findByUserIdAndProductId(UUID userId, UUID productId) {
        return wishlistJpaRepository.findByUserIdAndProductId(userId, productId);
    }

    @Override
    public List<Wishlist> findByUserId(UUID userId, int page, int size) {
        return wishlistJpaRepository.findByUserId(userId, PageRequest.of(page, size));
    }

    @Override
    public long countByUserId(UUID userId) {
        return wishlistJpaRepository.countByUserId(userId);
    }

    @Override
    public boolean existsByUserIdAndProductId(UUID userId, UUID productId) {
        return wishlistJpaRepository.existsByUserIdAndProductId(userId, productId);
    }

    @Override
    public void delete(Wishlist wishlist) {
        wishlistJpaRepository.delete(wishlist);
    }
}
