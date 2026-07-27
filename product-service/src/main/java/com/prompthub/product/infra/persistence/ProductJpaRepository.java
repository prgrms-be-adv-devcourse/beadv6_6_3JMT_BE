package com.prompthub.product.infra.persistence;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.model.enums.ProductType;
import com.prompthub.product.domain.model.enums.ReviewStatus;
import com.prompthub.product.domain.model.projection.ProductListProjection;
import com.prompthub.product.domain.model.projection.ProductReviewProjection;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
			and p.id <> :productId
			and p.productType = :productType
		group by p.id, p.parentId, p.name, p.productType, p.model, p.amount, p.salesCount, p.sellerId,
			p.description, p.thumbnailUrl, p.createdAt, p.updatedAt
		order by coalesce((select sum(m.salesCount) from Product m where coalesce(m.parentId, m.id) = coalesce(p.parentId, p.id) and m.deletedAt is null), 0) desc, p.createdAt desc
		""")
	List<ProductListProjection> findRelatedProducts(
		@Param("productId") UUID productId,
		@Param("productType") ProductType productType,
		@Param("onSaleStatus") ProductStatus onSaleStatus,
		@Param("activeReviewStatus") ReviewStatus activeReviewStatus,
		Pageable pageable
	);

	default List<ProductListProjection> findRelatedProducts(
		UUID productId,
		ProductType productType,
		int limit
	) {
		return findRelatedProducts(
			productId,
			productType,
			ProductStatus.ON_SALE,
			ReviewStatus.ACTIVE,
			PageRequest.of(0, limit)
		);
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
}
