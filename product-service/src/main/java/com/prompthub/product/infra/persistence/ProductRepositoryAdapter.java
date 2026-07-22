package com.prompthub.product.infra.persistence;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.model.enums.ProductType;
import com.prompthub.product.domain.model.projection.ProductListProjection;
import com.prompthub.product.domain.model.projection.ProductReviewProjection;
import com.prompthub.product.domain.repository.ProductRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
	public Map<UUID, Double> getAverageRatings(List<UUID> familyRootIds) {
		return productJpaRepository.getAverageRatings(familyRootIds);
	}

	@Override
	public long sumSalesCountByFamilyRootId(UUID familyRootId) {
		return productJpaRepository.sumSalesCountByFamilyRootId(familyRootId);
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
	public long countFamiliesBySellerId(UUID sellerId) {
		return productJpaRepository.countFamiliesBySellerId(sellerId);
	}

	@Override
	public long sumSalesCountBySellerId(UUID sellerId) {
		return productJpaRepository.sumSalesCountBySellerId(sellerId);
	}

	@Override
	public long countOnSaleProductsBySellerId(UUID sellerId) {
		return productJpaRepository.countBySellerIdAndStatusAndDeletedAtIsNull(sellerId, ProductStatus.ON_SALE);
	}

	@Override
	public List<Product> findAllByFamilyRootIds(List<UUID> familyRootIds) {
		return productJpaRepository.findAllByFamilyRootIds(familyRootIds);
	}
}
