package com.prompthub.user.wishlist.infrastructure.persistence;

import com.prompthub.user.wishlist.domain.model.Wishlist;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WishlistJpaRepository extends JpaRepository<Wishlist, UUID> {

    Optional<Wishlist> findByUserIdAndProductId(UUID userId, UUID productId);

    List<Wishlist> findByUserId(UUID userId, Pageable pageable);

    long countByUserId(UUID userId);

    boolean existsByUserIdAndProductId(UUID userId, UUID productId);
}
