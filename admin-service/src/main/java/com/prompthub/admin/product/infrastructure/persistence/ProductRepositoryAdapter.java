package com.prompthub.admin.product.infrastructure.persistence;

import com.prompthub.admin.product.domain.model.ProductListFilter;
import com.prompthub.admin.product.domain.model.entity.Product;
import com.prompthub.admin.product.domain.repository.ProductRepository;
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
public class ProductRepositoryAdapter implements ProductRepository {

	private final ProductJpaRepository productJpaRepository;

	@Override
	public Optional<Product> findById(UUID productId) {
		return productJpaRepository.findById(productId);
	}

	@Override
	public Product save(Product product) {
		return productJpaRepository.save(product);
	}

	@Override
	public List<Product> findAllByFamilyRootIds(List<UUID> familyRootIds) {
		return productJpaRepository.findAllByFamilyRootIds(familyRootIds);
	}

	@Override
	public Page<Product> findProducts(ProductListFilter filter, Pageable pageable) {
		return productJpaRepository.findAll(buildSpec(filter), pageable);
	}

	private Specification<Product> buildSpec(ProductListFilter filter) {
		return ProductSpecifications.withStatus(filter.status())
			.and(ProductSpecifications.notDeleted())
			.and(ProductSpecifications.withKeyword(filter.keyword(), filter.keywordSellerIds()));
	}
}
