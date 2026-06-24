package com.prompthub.product.infra.persistence;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.projection.ProductListProjection;
import com.prompthub.product.domain.model.projection.ProductReviewProjection;
import com.prompthub.product.domain.repository.ProductRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ProductRepositoryAdapter implements ProductRepository {

	private final ProductJpaRepository productJpaRepository;

	@Override
	public Optional<Product> findById(UUID productId) {
		return productJpaRepository.findById(productId);
	}

	@Override
	public List<ProductListProjection> findPublicProducts(String keyword, String category, String sort, Pageable pageable) {
		return productJpaRepository.findPublicProducts(keyword, category, sort, pageable);
	}

	@Override
	public long countPublicProducts(String keyword, String category) {
		return productJpaRepository.countPublicProducts(keyword, category);
	}

	@Override
	public double getAverageRating(UUID productId) {
		return productJpaRepository.getAverageRating(productId);
	}

	@Override
	public List<ProductListProjection> findRelatedProducts(UUID productId, UUID categoryId, int limit) {
		return productJpaRepository.findRelatedProducts(productId, categoryId, limit);
	}

	@Override
	public List<ProductReviewProjection> findActiveReviews(UUID productId) {
		return productJpaRepository.findActiveReviews(productId);
	}
}
