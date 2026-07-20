package com.prompthub.admin.product.domain.repository;

import com.prompthub.admin.product.domain.model.entity.Product;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository {

	Optional<Product> findById(UUID productId);

	Product save(Product product);

	List<Product> findPendingReviewProducts();

	List<Product> findAllByFamilyRootIds(List<UUID> familyRootIds);
}
