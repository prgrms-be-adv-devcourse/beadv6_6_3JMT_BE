package com.prompthub.admin.product.repository;

import com.prompthub.admin.product.dto.ProductListFilter;
import com.prompthub.admin.product.entity.Product;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ProductRepository {

	private final ProductJpaRepository productJpaRepository;

	public Optional<Product> findById(UUID productId) {
		return productJpaRepository.findById(productId);
	}

	public Product save(Product product) {
		return productJpaRepository.save(product);
	}

	public List<Product> findAllByFamilyRootIds(List<UUID> familyRootIds) {
		return productJpaRepository.findAllByFamilyRootIds(familyRootIds);
	}

	public Page<Product> findProducts(ProductListFilter filter, Pageable pageable) {
		return productJpaRepository.findAll(buildSpec(filter), pageable);
	}

	private Specification<Product> buildSpec(ProductListFilter filter) {
		return ProductSpecifications.withStatus(filter.status())
			.and(ProductSpecifications.notDeleted())
			.and(ProductSpecifications.withKeyword(filter.keyword(), filter.keywordSellerIds()));
	}
}
