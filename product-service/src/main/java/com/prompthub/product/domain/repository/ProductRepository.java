package com.prompthub.product.domain.repository;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.ProductType;
import com.prompthub.product.domain.model.projection.ProductListProjection;
import com.prompthub.product.domain.model.projection.ProductReviewProjection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;

public interface ProductRepository {

	Optional<Product> findById(UUID productId);

	Product save(Product product);

	List<ProductListProjection> findPublicProducts(String keyword, String productType, String sort, Pageable pageable);

	long countPublicProducts(String keyword, String productType);

	double getAverageRating(UUID productId);

	List<ProductListProjection> findRelatedProducts(UUID productId, ProductType productType, int limit);

	List<ProductReviewProjection> findActiveReviews(UUID productId);

	List<Product> findBySellerId(UUID sellerId);

	List<Product> findAllByIdIn(List<UUID> productIds);

	List<Product> findOnSaleByIdIn(List<UUID> productIds);

	long countBySellerId(UUID sellerId);

	long countOnSaleProductsBySellerId(UUID sellerId);

	List<Product> findPendingReviewProducts();

	List<Product> findAllAdminProducts();
}
