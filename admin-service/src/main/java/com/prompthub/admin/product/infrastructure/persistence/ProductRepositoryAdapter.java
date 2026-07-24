package com.prompthub.admin.product.infrastructure.persistence;

import com.prompthub.admin.product.domain.model.entity.Product;
import com.prompthub.admin.product.domain.model.enums.ProductStatus;
import com.prompthub.admin.product.domain.repository.ProductRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
	public List<Product> findPendingReviewProducts() {
		return productJpaRepository.findPendingReviewProducts();
	}

	@Override
	public List<Product> findAllByFamilyRootIds(List<UUID> familyRootIds) {
		return productJpaRepository.findAllByFamilyRootIds(familyRootIds);
	}

	@Override
	public List<Product> findProducts(ProductStatus status, String keyword, List<UUID> keywordSellerIds, int page, int size) {
		PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
		return productJpaRepository.findAll(buildSpec(status, keyword, keywordSellerIds), pageRequest).getContent();
	}

	@Override
	public long countProducts(ProductStatus status, String keyword, List<UUID> keywordSellerIds) {
		return productJpaRepository.count(buildSpec(status, keyword, keywordSellerIds));
	}

	private Specification<Product> buildSpec(ProductStatus status, String keyword, List<UUID> keywordSellerIds) {
		return ProductSpecifications.withStatus(status)
			.and(ProductSpecifications.notDeleted())
			.and(ProductSpecifications.withKeyword(keyword, keywordSellerIds));
	}
}
