package com.prompthub.product.infra.persistence;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.model.enums.ReviewStatus;
import com.prompthub.product.domain.model.projection.ProductListProjection;
import com.prompthub.product.domain.model.projection.ProductReviewProjection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductJpaRepository extends JpaRepository<Product, UUID> {

	@Query("""
		select new com.prompthub.product.domain.model.projection.ProductListProjection(
			p.id,
			p.name,
			coalesce(c.code, ''),
			coalesce(c.icon, ''),
			p.productType,
			p.model,
			p.amount,
			coalesce(avg(r.rating), 0.0),
			p.salesCount,
			p.sellerId,
			p.description,
			p.thumbnailUrl,
			p.createdAt,
			p.updatedAt
		)
		from Product p
		left join p.category c
		left join Review r on r.product = p and r.status = :activeReviewStatus and r.deletedAt is null
		where p.status = :onSaleStatus
			and p.deletedAt is null
			and (:category = 'all' or c.code = :category)
			and (:keyword = ''
				or lower(p.name) like concat('%', :keyword, '%')
				or lower(p.description) like concat('%', :keyword, '%'))
		group by p.id, p.name, c.code, c.icon, p.productType, p.model, p.amount, p.salesCount, p.sellerId,
			p.description, p.thumbnailUrl, p.createdAt, p.updatedAt
		order by
			case when :sort = 'rating' then coalesce(avg(r.rating), 0.0) end desc,
			case when :sort = 'price-asc' then p.amount end asc,
			case when :sort = 'price-desc' then p.amount end desc,
			p.salesCount desc,
			p.createdAt desc
		""")
	List<ProductListProjection> findPublicProducts(
		@Param("keyword") String keyword,
		@Param("category") String category,
		@Param("sort") String sort,
		@Param("onSaleStatus") ProductStatus onSaleStatus,
		@Param("activeReviewStatus") ReviewStatus activeReviewStatus,
		Pageable pageable
	);

	default List<ProductListProjection> findPublicProducts(
		String keyword,
		String category,
		String sort,
		Pageable pageable
	) {
		return findPublicProducts(keyword, category, sort, ProductStatus.ON_SALE, ReviewStatus.ACTIVE, pageable);
	}

	@Query("""
		select count(p)
		from Product p
		left join p.category c
		where p.status = :onSaleStatus
			and p.deletedAt is null
			and (:category = 'all' or c.code = :category)
			and (:keyword = ''
				or lower(p.name) like concat('%', :keyword, '%')
				or lower(p.description) like concat('%', :keyword, '%'))
		""")
	long countPublicProducts(
		@Param("keyword") String keyword,
		@Param("category") String category,
		@Param("onSaleStatus") ProductStatus onSaleStatus
	);

	default long countPublicProducts(String keyword, String category) {
		return countPublicProducts(keyword, category, ProductStatus.ON_SALE);
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
		select new com.prompthub.product.domain.model.projection.ProductListProjection(
			p.id,
			p.name,
			coalesce(c.code, ''),
			coalesce(c.icon, ''),
			p.productType,
			p.model,
			p.amount,
			coalesce(avg(r.rating), 0.0),
			p.salesCount,
			p.sellerId,
			p.description,
			p.thumbnailUrl,
			p.createdAt,
			p.updatedAt
		)
		from Product p
		left join p.category c
		left join Review r on r.product = p and r.status = :activeReviewStatus and r.deletedAt is null
		where p.status = :onSaleStatus
			and p.deletedAt is null
			and p.id <> :productId
			and (:categoryId is null or p.categoryId = :categoryId)
		group by p.id, p.name, c.code, c.icon, p.productType, p.model, p.amount, p.salesCount, p.sellerId,
			p.description, p.thumbnailUrl, p.createdAt, p.updatedAt
		order by p.salesCount desc, p.createdAt desc
		""")
	List<ProductListProjection> findRelatedProducts(
		@Param("productId") UUID productId,
		@Param("categoryId") UUID categoryId,
		@Param("onSaleStatus") ProductStatus onSaleStatus,
		@Param("activeReviewStatus") ReviewStatus activeReviewStatus,
		Pageable pageable
	);

	default List<ProductListProjection> findRelatedProducts(UUID productId, UUID categoryId, int limit) {
		return findRelatedProducts(
			productId,
			categoryId,
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

	long countBySellerIdAndDeletedAtIsNull(UUID sellerId);

	long countBySellerIdAndStatusAndDeletedAtIsNull(UUID sellerId, ProductStatus status);

	List<Product> findAllByIdInAndStatusAndDeletedAtIsNull(List<UUID> ids, ProductStatus status);

	@Query("""
		select p
		from Product p
		where p.status = com.prompthub.product.domain.model.enums.ProductStatus.PENDING_REVIEW
			and p.deletedAt is null
		order by p.createdAt asc
		""")
	List<Product> findPendingReviewProducts();

	@Query("""
		select p
		from Product p
		where p.deletedAt is null
		order by p.createdAt desc
		""")
	List<Product> findAllAdminProducts();
}
