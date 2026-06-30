package com.prompthub.product.domain.repository;

import com.prompthub.product.domain.model.entity.Review;
import java.util.Optional;
import java.util.UUID;

public interface ReviewRepository {

	Optional<Review> findByUserIdAndProductId(UUID userId, UUID productId);

	Review save(Review review);
}
