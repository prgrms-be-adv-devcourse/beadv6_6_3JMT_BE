package com.prompthub.admin.product.infrastructure.persistence;

import com.prompthub.admin.product.domain.model.entity.Product;
import com.prompthub.admin.product.domain.repository.AdminProductRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AdminProductRepositoryAdapter implements AdminProductRepository {

	private final AdminProductJpaRepository adminProductJpaRepository;

	@Override
	public Optional<Product> findById(UUID productId) {
		return adminProductJpaRepository.findById(productId);
	}

	@Override
	public Product save(Product product) {
		return adminProductJpaRepository.save(product);
	}

	@Override
	public List<Product> findPendingReviewProducts() {
		return adminProductJpaRepository.findPendingReviewProducts();
	}

	@Override
	public List<Product> findAllByFamilyRootIds(List<UUID> familyRootIds) {
		return adminProductJpaRepository.findAllByFamilyRootIds(familyRootIds);
	}
}
