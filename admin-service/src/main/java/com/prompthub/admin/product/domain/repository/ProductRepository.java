package com.prompthub.admin.product.domain.repository;

import com.prompthub.admin.product.domain.model.ProductListFilter;
import com.prompthub.admin.product.domain.model.entity.Product;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ProductRepository {

	Optional<Product> findById(UUID productId);

	Product save(Product product);

	Page<Product> findProducts(ProductListFilter filter, Pageable pageable);

	List<Product> findAllByFamilyRootIds(List<UUID> familyRootIds);
}
