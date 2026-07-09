package com.prompthub.product.infra.persistence;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.model.enums.ProductType;
import com.prompthub.product.domain.model.projection.ProductListProjection;
import com.prompthub.product.domain.model.projection.ProductReviewProjection;
import com.prompthub.product.domain.repository.ProductRepository;
import java.util.ArrayList;
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
	public Product save(Product product) {
		return productJpaRepository.save(product);
	}

	@Override
	public List<ProductListProjection> findPublicProducts(String keyword, String productType, String sort, Pageable pageable) {
		return productJpaRepository.findPublicProducts(keyword, productType, sort, pageable);
	}

	@Override
	public long countPublicProducts(String keyword, String productType) {
		return productJpaRepository.countPublicProducts(keyword, productType);
	}

	@Override
	public double getAverageRating(UUID productId) {
		return productJpaRepository.getAverageRating(productId);
	}

	@Override
	public List<ProductListProjection> findRelatedProducts(UUID productId, ProductType productType, int limit) {
		return productJpaRepository.findRelatedProducts(productId, productType, limit);
	}

	@Override
	public List<ProductReviewProjection> findActiveReviews(UUID productId) {
		return productJpaRepository.findActiveReviews(productId);
	}

	@Override
	public List<Product> findBySellerId(UUID sellerId) {
		return productJpaRepository.findBySellerId(sellerId);
	}

	@Override
	public List<Product> findAllByIdIn(List<UUID> productIds) {
		return new ArrayList<>(productJpaRepository.findAllById(productIds));
	}

	@Override
	public List<Product> findOnSaleByIdIn(List<UUID> productIds) {
		return productJpaRepository.findAllByIdInAndStatusAndDeletedAtIsNull(productIds, ProductStatus.ON_SALE);
	}

	@Override
	public long countBySellerId(UUID sellerId) {
		return productJpaRepository.countBySellerIdAndDeletedAtIsNull(sellerId);
	}

	@Override
	public long countOnSaleProductsBySellerId(UUID sellerId) {
		return productJpaRepository.countBySellerIdAndStatusAndDeletedAtIsNull(sellerId, ProductStatus.ON_SALE);
	}

	@Override
	public List<Product> findPendingReviewProducts() {
		return productJpaRepository.findPendingReviewProducts();
	}

	@Override
	public List<Product> findAllAdminProducts() {
		return productJpaRepository.findAllAdminProducts();
	}
}
