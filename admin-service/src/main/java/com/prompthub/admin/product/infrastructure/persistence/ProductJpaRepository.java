package com.prompthub.admin.product.infrastructure.persistence;

import com.prompthub.admin.product.domain.model.entity.Product;
import com.prompthub.admin.product.domain.model.enums.ProductStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductJpaRepository extends JpaRepository<Product, UUID> {

	@Query("""
		select p
		from Product p
		where p.status = :status
			and p.deletedAt is null
		order by p.createdAt asc
		""")
	List<Product> findByStatus(@Param("status") ProductStatus status);

	default List<Product> findPendingReviewProducts() {
		return findByStatus(ProductStatus.PENDING_REVIEW);
	}

	@Query("""
		select p
		from Product p
		where p.id in :familyRootIds
			or p.parentId in :familyRootIds
		""")
	List<Product> findAllByFamilyRootIds(@Param("familyRootIds") List<UUID> familyRootIds);
}
