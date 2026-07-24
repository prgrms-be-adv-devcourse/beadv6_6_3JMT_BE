package com.prompthub.admin.product.infrastructure.persistence;

import com.prompthub.admin.product.domain.model.entity.Product;
import com.prompthub.admin.product.domain.model.enums.ProductStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

public class ProductSpecifications {

	private ProductSpecifications() {
	}

	public static Specification<Product> withStatus(ProductStatus status) {
		if (status == null) return (root, query, cb) -> root.get("status").in(ProductStatus.adminVisibleStatuses());
		return (root, query, cb) -> cb.equal(root.get("status"), status);
	}

	public static Specification<Product> notDeleted() {
		return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
	}

	public static Specification<Product> withKeyword(String keyword, List<UUID> sellerIds) {
		if (keyword == null || keyword.isBlank()) return (root, query, cb) -> cb.conjunction();
		return (root, query, cb) -> {
			String pattern = "%" + keyword.toLowerCase() + "%";
			var titleLike = cb.like(cb.lower(root.get("name")), pattern);
			if (sellerIds == null || sellerIds.isEmpty()) return titleLike;
			return cb.or(titleLike, root.get("sellerId").in(sellerIds));
		};
	}
}
