package com.prompthub.product.domain.repository;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.ProductStatus;
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

	Map<UUID, Long> getSalesCounts(List<UUID> familyRootIds);

	long sumSalesCountByFamilyRootId(UUID familyRootId);

	long sumViewCountByFamilyRootId(UUID familyRootId);

	boolean incrementViewCount(UUID productId, LocalDateTime viewedAt);

	/** 주어진 id들의 목록 표시용 정보(평점·family 판매수 포함). 순서는 보장하지 않는다. */
	List<ProductListProjection> findProjectionsByIds(List<UUID> productIds);

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

	/** ES 문서에 실을 임베딩을 상품 ID로 묶어 돌려준다. 아직 임베딩이 없는 상품은 결과에 없다. */
	Map<UUID, float[]> findEmbeddings(List<UUID> productIds);

	/**
	 * 같은 {@code contentHash}를 가진 <b>다른 판매자</b>의, 이 상품보다 <b>먼저 해시가 확정된</b>
	 * PROMPT 상품을 찾는다. 있으면 그 id가 {@code duplicateOfProductId}다(ADR-0011).
	 *
	 * <p>비교 기준은 {@code content_hash_at}(DB 트리거가 찍은 시각)이지 {@code createdAt}이
	 * 아니다 — 무해한 재편집으로는 순서가 밀리지 않는다.
	 */
	Optional<UUID> findDuplicateOfProductId(UUID productId, String contentHash, UUID sellerId);

	/** stale-after를 넘긴 검수 요청 재발행 후보 ID를 오래 대기한 순으로 최대 batchSize개 돌려준다. */
	List<UUID> findStaleInspectionRequestCandidateIds(LocalDateTime cutoff, int batchSize);

	/**
	 * 조건(PENDING_REVIEW·retryCount=0·cutoff 이전·미삭제)을 만족하는 상품 하나를 원자적으로
	 * 선점한다. 조회 후 별도 저장 방식이 아니라 조건부 UPDATE라, 같은 상품을 여러 인스턴스가
	 * 동시에 집어도 영향 row가 1인 실행만 true를 받는다.
	 */
	boolean claimInspectionRequestRetry(UUID productId, LocalDateTime cutoff);
}
