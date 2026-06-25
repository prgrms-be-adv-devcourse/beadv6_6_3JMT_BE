package com.prompthub.product.infra.persistence;

import com.prompthub.product.domain.model.entity.Review;
import com.prompthub.product.domain.repository.ReviewRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ReviewRepositoryAdapter implements ReviewRepository {

	private final ReviewJpaRepository reviewJpaRepository;

	@Override
	public Optional<Review> findByUserIdAndProductId(UUID userId, UUID productId) {
		return reviewJpaRepository.findByUserIdAndProductId(userId, productId);
	}

	@Override
	public Review save(Review review) {
		return reviewJpaRepository.save(review);
	}
}
