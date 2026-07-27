package com.prompthub.product.infra.persistence;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.model.enums.ProductType;
import com.prompthub.product.domain.model.projection.ProductListProjection;
import com.prompthub.product.domain.model.projection.ProductReviewProjection;
import com.prompthub.product.domain.repository.ProductRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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
	public long sumViewCountByFamilyRootId(UUID familyRootId) {
		return productJpaRepository.sumViewCountByFamilyRootId(familyRootId);
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

	@Override
	public List<Product> findAllByStatus(ProductStatus productStatus) {
		return productJpaRepository.findByStatusAndDeletedAtIsNull(productStatus);
	}

	@Override
	public List<UUID> findChangedFamilyRootIds(LocalDateTime since) {
		return productJpaRepository.findChangedFamilyRootIds(since);
	}

	@Override
	public Map<UUID, String> findEmbeddingSourceHashes(List<UUID> productIds) {
		if (productIds.isEmpty()) {
			return Map.of();
		}
		return productJpaRepository.findEmbeddingSourceHashRows(productIds).stream()
			.collect(Collectors.toMap(row -> (UUID) row[0], row -> (String) row[1]));
	}

	/**
	 * 상품 하나당 트랜잭션 하나다. 배치가 수십 건을 도는 동안 하나가 실패해도 앞서 저장한
	 * 임베딩까지 되돌아가지 않게 한다.
	 */
	@Override
	@Transactional
	public void updateEmbedding(UUID productId, float[] embedding, String sourceHash) {
		productJpaRepository.updateEmbedding(productId, toVectorLiteral(embedding), sourceHash);
	}

	/** pgvector는 {@code [0.1,0.2,...]} 형태의 텍스트를 vector로 캐스팅해 받는다. */
	private String toVectorLiteral(float[] embedding) {
		StringJoiner joiner = new StringJoiner(",", "[", "]");
		for (float value : embedding) {
			joiner.add(Float.toString(value));
		}
		return joiner.toString();
	}
}
