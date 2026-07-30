package com.prompthub.product.infra.persistence;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.model.projection.ProductListProjection;
import com.prompthub.product.domain.model.projection.ProductReviewProjection;
import com.prompthub.product.domain.model.projection.SimilarProductProjection;
import com.prompthub.product.domain.repository.ProductRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
	public List<ProductListProjection> findProjectionsByIds(List<UUID> productIds) {
		return productJpaRepository.findProjectionsByIds(productIds);
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

	/**
	 * 한 가족의 여러 버전이 동시에 ON_SALE이면(#699) 버전끼리 임베딩이 거의 같아 나란히
	 * 후보에 들어온다. 행이 이미 거리순이므로 가족당 첫 행(최근접)만 남긴다 — SQL의
	 * {@code DISTINCT ON}은 HNSW 인덱스 조건을 깨뜨려 여기서 걸러낸다.
	 */
	@Override
	public List<SimilarProductProjection> findSimilarProducts(UUID productId, UUID familyRootId, int candidates) {
		Map<UUID, SimilarProductProjection> firstPerFamily = new LinkedHashMap<>();
		for (Object[] row : productJpaRepository.findSimilarProductRows(productId, familyRootId, candidates)) {
			firstPerFamily.putIfAbsent((UUID) row[3], new SimilarProductProjection(
				(UUID) row[0], (String) row[1], ((Number) row[2]).doubleValue()));
		}
		return List.copyOf(firstPerFamily.values());
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

	@Override
	public Map<UUID, float[]> findEmbeddings(List<UUID> productIds) {
		if (productIds.isEmpty()) {
			return Map.of();
		}
		return productJpaRepository.findEmbeddingRows(productIds).stream()
			.collect(Collectors.toMap(row -> (UUID) row[0], row -> fromVectorLiteral((String) row[1])));
	}

	@Override
	public Optional<UUID> findDuplicateOfProductId(UUID productId, String contentHash, UUID sellerId) {
		return productJpaRepository.findDuplicateOfProductId(productId, contentHash, sellerId);
	}

	/** {@link #toVectorLiteral}의 역변환. 우리 시스템이 쓴 값을 그대로 읽는 왕복이라 별도 검증은 두지 않는다. */
	private float[] fromVectorLiteral(String literal) {
		String[] parts = literal.substring(1, literal.length() - 1).split(",");
		float[] embedding = new float[parts.length];
		for (int i = 0; i < parts.length; i++) {
			embedding[i] = Float.parseFloat(parts[i]);
		}
		return embedding;
	}
}
