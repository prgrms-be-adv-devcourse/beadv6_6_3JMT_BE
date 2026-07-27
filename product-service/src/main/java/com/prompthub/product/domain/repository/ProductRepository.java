package com.prompthub.product.domain.repository;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.model.enums.ProductType;
import com.prompthub.product.domain.model.projection.ProductListProjection;
import com.prompthub.product.domain.model.projection.ProductReviewProjection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;

public interface ProductRepository {

	Optional<Product> findById(UUID productId);

	Product save(Product product);

	List<ProductListProjection> findPublicProducts(String keyword, String productType, String sort, Pageable pageable);

	long countPublicProducts(String keyword, String productType);

	double getAverageRating(UUID productId);

	Map<UUID, Double> getAverageRatings(List<UUID> familyRootIds);

	long sumSalesCountByFamilyRootId(UUID familyRootId);

	long sumViewCountByFamilyRootId(UUID familyRootId);

	List<ProductListProjection> findRelatedProducts(UUID productId, ProductType productType, int limit);

	List<ProductReviewProjection> findActiveReviews(UUID productId);

	List<Product> findBySellerId(UUID sellerId);

	List<Product> findAllByIdIn(List<UUID> productIds);

	long countFamiliesBySellerId(UUID sellerId);

	long sumSalesCountBySellerId(UUID sellerId);

	long countOnSaleProductsBySellerId(UUID sellerId);

	List<Product> findAllByFamilyRootIds(List<UUID> familyRootIds);

	List<Product> findAllByStatus(ProductStatus productStatus);

	List<UUID> findChangedFamilyRootIds(LocalDateTime since);

	/**
	 * 임베딩 원문 해시를 상품 ID로 묶어 돌려준다. 아직 임베딩이 없는 상품은 결과에 없다.
	 *
	 * <p>{@code embedding}은 pgvector 타입이라 엔티티에 매핑하지 않는다. 해시만 따로 읽어
	 * "원문이 그대로면 다시 만들지 않는다"를 판단한다.
	 */
	Map<UUID, String> findEmbeddingSourceHashes(List<UUID> productIds);

	/** 임베딩과 그 원문 해시를 함께 저장한다. 둘은 항상 같이 바뀌어야 한다. */
	void updateEmbedding(UUID productId, float[] embedding, String sourceHash);
}
