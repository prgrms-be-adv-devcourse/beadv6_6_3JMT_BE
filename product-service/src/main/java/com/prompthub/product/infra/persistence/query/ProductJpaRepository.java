package com.prompthub.product.infra.persistence.query;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.model.enums.ReviewStatus;
import com.prompthub.product.domain.model.projection.ProductListProjection;
import com.prompthub.product.domain.model.projection.ProductReviewProjection;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductJpaRepository extends JpaRepository<Product, UUID> {

	@Query("""
		select new com.prompthub.product.domain.model.projection.ProductListProjection(
			p.id,
			p.name,
			str(p.productType),
			p.model,
			p.amount,
			coalesce(avg(r.rating), 0.0),
			cast(coalesce((select sum(m.salesCount) from Product m where coalesce(m.parentId, m.id) = coalesce(p.parentId, p.id) and m.deletedAt is null), 0) as integer),
			p.sellerId,
			p.description,
			p.thumbnailUrl,
			p.createdAt,
			p.updatedAt
		)
		from Product p
		left join Review r on r.product.id = coalesce(p.parentId, p.id) and r.status = :activeReviewStatus and r.deletedAt is null
		where p.status = :onSaleStatus
			and p.deletedAt is null
			and (:productType = 'all' or str(p.productType) = :productType)
			and (:keyword = ''
				or lower(p.name) like concat('%', :keyword, '%')
				or lower(p.description) like concat('%', :keyword, '%'))
		group by p.id, p.parentId, p.name, p.productType, p.model, p.amount, p.salesCount, p.sellerId,
			p.description, p.thumbnailUrl, p.createdAt, p.updatedAt
		order by
			case when :sort = 'rating' then coalesce(avg(r.rating), 0.0) end desc,
			case when :sort = 'price-asc' then p.amount end asc,
			case when :sort = 'price-desc' then p.amount end desc,
			coalesce((select sum(m.salesCount) from Product m where coalesce(m.parentId, m.id) = coalesce(p.parentId, p.id) and m.deletedAt is null), 0) desc,
			p.createdAt desc
		""")
	List<ProductListProjection> findPublicProducts(
		@Param("keyword") String keyword,
		@Param("productType") String productType,
		@Param("sort") String sort,
		@Param("onSaleStatus") ProductStatus onSaleStatus,
		@Param("activeReviewStatus") ReviewStatus activeReviewStatus,
		Pageable pageable
	);

	default List<ProductListProjection> findPublicProducts(
		String keyword,
		String productType,
		String sort,
		Pageable pageable
	) {
		return findPublicProducts(keyword, productType, sort, ProductStatus.ON_SALE, ReviewStatus.ACTIVE, pageable);
	}

	@Query("""
		select count(p)
		from Product p
		where p.status = :onSaleStatus
			and p.deletedAt is null
			and (:productType = 'all' or str(p.productType) = :productType)
			and (:keyword = ''
				or lower(p.name) like concat('%', :keyword, '%')
				or lower(p.description) like concat('%', :keyword, '%'))
		""")
	long countPublicProducts(
		@Param("keyword") String keyword,
		@Param("productType") String productType,
		@Param("onSaleStatus") ProductStatus onSaleStatus
	);

	default long countPublicProducts(String keyword, String productType) {
		return countPublicProducts(keyword, productType, ProductStatus.ON_SALE);
	}

	@Query("""
		select coalesce(avg(r.rating), 0.0)
		from Review r
		where r.product.id = :productId
			and r.status = :activeReviewStatus
			and r.deletedAt is null
		""")
	double getAverageRating(
		@Param("productId") UUID productId,
		@Param("activeReviewStatus") ReviewStatus activeReviewStatus
	);

	default double getAverageRating(UUID productId) {
		return getAverageRating(productId, ReviewStatus.ACTIVE);
	}

	@Query("""
		select r.product.id, coalesce(avg(r.rating), 0.0)
		from Review r
		where r.product.id in :familyRootIds
			and r.status = :activeReviewStatus
			and r.deletedAt is null
		group by r.product.id
		""")
	List<Object[]> findAverageRatingsByFamilyRootIds(
		@Param("familyRootIds") List<UUID> familyRootIds,
		@Param("activeReviewStatus") ReviewStatus activeReviewStatus
	);

	default Map<UUID, Double> getAverageRatings(List<UUID> familyRootIds) {
		if (familyRootIds.isEmpty()) {
			return Map.of();
		}
		return findAverageRatingsByFamilyRootIds(familyRootIds, ReviewStatus.ACTIVE).stream()
			.collect(Collectors.toMap(row -> (UUID) row[0], row -> (Double) row[1]));
	}

	@Query("""
		select coalesce(sum(p.salesCount), 0)
		from Product p
		where coalesce(p.parentId, p.id) = :familyRootId
			and p.deletedAt is null
		""")
	long sumSalesCountByFamilyRootId(@Param("familyRootId") UUID familyRootId);

	@Query("""
		select coalesce(sum(p.viewCount), 0)
		from Product p
		where coalesce(p.parentId, p.id) = :familyRootId
			and p.deletedAt is null
		""")
	long sumViewCountByFamilyRootId(@Param("familyRootId") UUID familyRootId);

	/**
	 * 주어진 id들의 목록 표시용 정보를 한 번에 가져온다.
	 *
	 * <p>평점 평균과 family 판매수 합산이 필요해 엔티티만으로는 만들 수 없다. 정렬은 걸지
	 * 않는다 — 순서는 호출자가 정하고(추천 순위 등) 여기서 덮으면 안 된다.
	 */
	@Query("""
		select new com.prompthub.product.domain.model.projection.ProductListProjection(
			p.id,
			p.name,
			str(p.productType),
			p.model,
			p.amount,
			coalesce(avg(r.rating), 0.0),
			cast(coalesce((select sum(m.salesCount) from Product m where coalesce(m.parentId, m.id) = coalesce(p.parentId, p.id) and m.deletedAt is null), 0) as integer),
			p.sellerId,
			p.description,
			p.thumbnailUrl,
			p.createdAt,
			p.updatedAt
		)
		from Product p
		left join Review r on r.product.id = coalesce(p.parentId, p.id) and r.status = :activeReviewStatus and r.deletedAt is null
		where p.id in :productIds
			and p.deletedAt is null
		group by p.id, p.parentId, p.name, p.productType, p.model, p.amount, p.salesCount, p.sellerId,
			p.description, p.thumbnailUrl, p.createdAt, p.updatedAt
		""")
	List<ProductListProjection> findProjectionsByIds(
		@Param("productIds") List<UUID> productIds,
		@Param("activeReviewStatus") ReviewStatus activeReviewStatus
	);

	default List<ProductListProjection> findProjectionsByIds(List<UUID> productIds) {
		if (productIds.isEmpty()) {
			return List.of();
		}
		return findProjectionsByIds(productIds, ReviewStatus.ACTIVE);
	}

	@Query("""
		select new com.prompthub.product.domain.model.projection.ProductReviewProjection(
			r.id,
			r.userId,
			r.rating,
			r.content,
			r.createdAt,
			r.updatedAt
		)
		from Review r
		where r.product.id = :productId
			and r.status = :activeReviewStatus
			and r.deletedAt is null
		order by r.createdAt desc
		""")
	List<ProductReviewProjection> findActiveReviews(
		@Param("productId") UUID productId,
		@Param("activeReviewStatus") ReviewStatus activeReviewStatus
	);

	default List<ProductReviewProjection> findActiveReviews(UUID productId) {
		return findActiveReviews(productId, ReviewStatus.ACTIVE);
	}

	@Query("""
		select p
		from Product p
		where p.sellerId = :sellerId
			and p.deletedAt is null
		order by p.createdAt desc
		""")
	List<Product> findBySellerId(@Param("sellerId") UUID sellerId);

	@Query("""
		select count(distinct coalesce(p.parentId, p.id))
		from Product p
		where p.sellerId = :sellerId
			and p.deletedAt is null
		""")
	long countFamiliesBySellerId(@Param("sellerId") UUID sellerId);

	@Query("""
		select coalesce(sum(p.salesCount), 0)
		from Product p
		where p.sellerId = :sellerId
			and p.deletedAt is null
		""")
	long sumSalesCountBySellerId(@Param("sellerId") UUID sellerId);

	long countBySellerIdAndStatusAndDeletedAtIsNull(UUID sellerId, ProductStatus status);

	@Query("""
		select p
		from Product p
		where p.id in :familyRootIds
			or p.parentId in :familyRootIds
		""")
	List<Product> findAllByFamilyRootIds(@Param("familyRootIds") List<UUID> familyRootIds);

	List<Product> findByStatusAndDeletedAtIsNull(ProductStatus status);

	@Query("""
		select distinct coalesce(p.parentId, p.id)
		from Product p
		where p.updatedAt >= :since
			and p.deletedAt is null
		""")
	List<UUID> findFamilyRootIdsByProductUpdatedSince(@Param("since") LocalDateTime since);

	@Query("""
		select distinct r.product.id
		from Review r
		where r.updatedAt >= :since
			and r.deletedAt is null
		""")
	List<UUID> findFamilyRootIdsByReviewUpdatedSince(@Param("since") LocalDateTime since);

	default List<UUID> findChangedFamilyRootIds(LocalDateTime since) {
		Set<UUID> changed = new LinkedHashSet<>(findFamilyRootIdsByProductUpdatedSince(since));
		changed.addAll(findFamilyRootIdsByReviewUpdatedSince(since));
		return List.copyOf(changed);
	}

	/**
	 * {@code embedding}은 pgvector 타입이라 엔티티에 매핑돼 있지 않다. 짝이 되는
	 * {@code embedding_source_hash}도 같은 네이티브 쿼리로 다뤄 한쪽만 JPA로 새는 일이 없게 한다.
	 */
	@Query(value = """
		select id, embedding_source_hash
		from product
		where id in (:productIds)
			and embedding_source_hash is not null
		""", nativeQuery = true)
	List<Object[]> findEmbeddingSourceHashRows(@Param("productIds") List<UUID> productIds);

	/**
	 * 기준 상품과 임베딩이 가까운 순으로 후보를 돌려준다.
	 *
	 * <p>정렬식을 순수 거리 연산자로 두어야 ON_SALE 부분 HNSW 인덱스를 탄다. 여기에 유형
	 * 가산점 같은 산술을 얹으면 표현식이 되어 인덱스를 못 쓰고 풀스캔이 된다 — 그래서 재정렬은
	 * 앱에서 한다.
	 *
	 * <p>마지막 exists는 <b>기준 상품</b>에 임베딩이 있는지 본다. 없으면 서브쿼리가 NULL이 되고
	 * {@code <=> NULL}도 NULL이라 후보 행이 distance=NULL로 그대로 돌아온다(제외되지 않는다) —
	 * 이걸 primitive double로 받으면 NPE가 난다. 승인 직후 재조정 배치가 임베딩을 채우기 전까지
	 * 실제로 생기는 상태다. WHERE에만 두어 ORDER BY 식은 건드리지 않는다.
	 *
	 * <p>가족(family) 중복 제거는 SQL이 아니라 어댑터에서 한다 — {@code DISTINCT ON}은 정렬
	 * 선두를 가족 키로 바꿔 위의 HNSW 인덱스 조건을 깨뜨린다. family_root 컬럼은 그 어댑터
	 * 중복 제거용이다(#699).
	 *
	 * @return {@code [id(UUID), productType(String), distance(Double), familyRoot(UUID)]} 행 목록
	 */
	@Query(value = """
		select p.id, p.product_type,
		       p.embedding <=> (select embedding from product where id = :productId) as distance,
		       coalesce(p.parent_id, p.id) as family_root
		from product p
		where p.status = 'ON_SALE'
			and p.deleted_at is null
			and p.embedding is not null
			and coalesce(p.parent_id, p.id) <> :familyRootId
			and exists (select 1 from product b where b.id = :productId and b.embedding is not null)
		order by p.embedding <=> (select embedding from product where id = :productId)
		limit :candidates
		""", nativeQuery = true)
	List<Object[]> findSimilarProductRows(
		@Param("productId") UUID productId,
		@Param("familyRootId") UUID familyRootId,
		@Param("candidates") int candidates
	);

	/**
	 * 네이티브 UPDATE라 JPA auditing이 타지 않아 {@code updated_at}이 그대로 남는다. 이게
	 * 중요하다 — 임베딩을 쓸 때마다 updated_at이 바뀌면 그 상품이 다음 증분 재조정 대상으로
	 * 다시 걸려 배치가 자기 꼬리를 무는 루프가 된다.
	 */
	@Modifying
	@Query(value = """
		update product
		set embedding = cast(:embedding as vector),
			embedding_source_hash = :sourceHash
		where id = :productId
		""", nativeQuery = true)
	void updateEmbedding(
		@Param("productId") UUID productId,
		@Param("embedding") String embedding,
		@Param("sourceHash") String sourceHash
	);

	/** {@code embedding::text}로 캐스팅해 드라이버·타입 매핑과 무관하게 항상 문자열로 받는다. */
	@Query(value = """
		select id, embedding::text
		from product
		where id in (:productIds)
			and embedding is not null
		""", nativeQuery = true)
	List<Object[]> findEmbeddingRows(@Param("productIds") List<UUID> productIds);

	/**
	 * 같은 content_hash를 가진 다른 판매자의 PROMPT 상품 중, {@code productId}보다
	 * content_hash_at이 이른 순으로 후보를 돌려준다(ADR-0011 복제 탐지).
	 *
	 * <p>{@code content_hash_at}은 DB 트리거가 찍으므로, 앱이 든 {@code Product} 엔티티
	 * 값이 아니라 서브쿼리로 이 상품의 확정 시각을 다시 읽는다.
	 */
	@Query("""
		select p.id
		from Product p
		where p.productType = com.prompthub.product.domain.model.enums.ProductType.PROMPT
			and p.contentHash = :contentHash
			and p.sellerId <> :sellerId
			and p.status in :statuses
			and p.contentHashAt < (select p2.contentHashAt from Product p2 where p2.id = :productId)
		order by p.contentHashAt asc
		""")
	List<UUID> findEarlierDuplicateProductIds(
		@Param("productId") UUID productId,
		@Param("contentHash") String contentHash,
		@Param("sellerId") UUID sellerId,
		@Param("statuses") List<ProductStatus> statuses,
		Pageable pageable
	);

	default Optional<UUID> findDuplicateOfProductId(UUID productId, String contentHash, UUID sellerId) {
		return findEarlierDuplicateProductIds(
			productId, contentHash, sellerId,
			List.of(ProductStatus.ON_SALE, ProductStatus.PENDING_REVIEW),
			PageRequest.of(0, 1)
		).stream().findFirst();
	}

	@Query(value = """
		select id
		from product
		where status = 'PENDING_REVIEW'
			and inspection_request_retry_count = 0
			and inspection_requested_at <= :cutoff
			and deleted_at is null
		order by inspection_requested_at asc
		limit :batchSize
		""", nativeQuery = true)
	List<UUID> findStaleInspectionRequestCandidateIds(@Param("cutoff") LocalDateTime cutoff, @Param("batchSize") int batchSize);

	/**
	 * 조건부 선점 UPDATE. 영향 row 수가 1이어야 이번 실행이 재발행 권리를 가진다 — 조회 후
	 * 엔티티 값만 바꿔 저장하는 방식은 두 인스턴스가 같은 상품을 동시에 재발행할 수 있어 쓰지 않는다.
	 */
	@Modifying
	@Query(value = """
		update product
		set inspection_request_retry_count = 1
		where id = :productId
			and status = 'PENDING_REVIEW'
			and inspection_request_retry_count = 0
			and inspection_requested_at <= :cutoff
			and deleted_at is null
		""", nativeQuery = true)
	int claimInspectionRequestRetryRowCount(@Param("productId") UUID productId, @Param("cutoff") LocalDateTime cutoff);

	default boolean claimInspectionRequestRetry(UUID productId, LocalDateTime cutoff) {
		return claimInspectionRequestRetryRowCount(productId, cutoff) == 1;
	}
}
