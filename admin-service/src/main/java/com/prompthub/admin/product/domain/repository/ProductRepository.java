package com.prompthub.admin.product.domain.repository;

import com.prompthub.admin.product.domain.model.entity.Product;
import com.prompthub.admin.product.domain.model.enums.ProductStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository {

	Optional<Product> findById(UUID productId);

	Product save(Product product);

	List<Product> findProducts(ProductStatus status, String keyword, List<UUID> keywordSellerIds, int page, int size);

	long countProducts(ProductStatus status, String keyword, List<UUID> keywordSellerIds);

	List<Product> findAllByFamilyRootIds(List<UUID> familyRootIds);
}
