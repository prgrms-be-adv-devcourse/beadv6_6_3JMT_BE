package com.prompthub.product.infra.persistence;

import com.prompthub.product.domain.model.entity.Review;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewJpaRepository extends JpaRepository<Review, UUID> {

	Optional<Review> findByUserIdAndProductId(UUID userId, UUID productId);
}
