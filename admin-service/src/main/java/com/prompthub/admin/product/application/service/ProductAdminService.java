package com.prompthub.admin.product.application.service;

import com.prompthub.admin.global.exception.AdminErrorCode;
import com.prompthub.admin.product.application.usecase.ProductAdminUseCase;
import com.prompthub.admin.product.domain.exception.ProductAdminException;
import com.prompthub.admin.product.domain.model.entity.Product;
import com.prompthub.admin.product.domain.model.entity.ProductFamily;
import com.prompthub.admin.product.domain.model.enums.ProductStatus;
import com.prompthub.admin.product.domain.repository.AdminProductRepository;
import com.prompthub.admin.product.presentation.dto.response.AdminProductListItemResponse;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class ProductAdminService implements ProductAdminUseCase {

	private final AdminProductRepository adminProductRepository;

	@Override
	@Transactional(readOnly = true)
	public List<AdminProductListItemResponse> getPendingReviewProducts() {
		return adminProductRepository.findPendingReviewProducts().stream()
			.map(AdminProductListItemResponse::from)
			.toList();
	}

	@Override
	public void approveProduct(UUID productId) {
		Product target = getProductInPendingReview(productId);
		UUID familyRootId = target.familyRootId();
		ProductFamily family = ProductFamily.of(
			familyRootId,
			adminProductRepository.findAllByFamilyRootIds(List.of(familyRootId))
		);
		family.currentOnSale().ifPresent(previous -> {
			previous.supersede();
			adminProductRepository.save(previous);
		});
		target.approve();
		adminProductRepository.save(target);
	}

	@Override
	public void rejectProduct(UUID productId, String reason) {
		Product product = getProductInPendingReview(productId);
		product.reject(reason);
		adminProductRepository.save(product);
	}

	@Override
	public void revertProductToPendingReview(UUID productId) {
		Product target = adminProductRepository.findById(productId)
			.orElseThrow(() -> new ProductAdminException(AdminErrorCode.PRODUCT_NOT_FOUND));
		if (target.getStatus() != ProductStatus.ON_SALE && target.getStatus() != ProductStatus.REJECTED) {
			throw new ProductAdminException(AdminErrorCode.PRODUCT_INVALID_STATUS);
		}

		if (target.getStatus() == ProductStatus.ON_SALE) {
			UUID familyRootId = target.familyRootId();
			ProductFamily family = ProductFamily.of(
				familyRootId,
				adminProductRepository.findAllByFamilyRootIds(List.of(familyRootId))
			);
			family.mostRecentSuperseded().ifPresent(paired -> {
				paired.restoreFromSuperseded();
				adminProductRepository.save(paired);
			});
		}

		target.revertToPendingReview();
		adminProductRepository.save(target);
	}

	private Product getProductInPendingReview(UUID productId) {
		Product product = adminProductRepository.findById(productId)
			.orElseThrow(() -> new ProductAdminException(AdminErrorCode.PRODUCT_NOT_FOUND));
		if (product.getStatus() != ProductStatus.PENDING_REVIEW) {
			throw new ProductAdminException(AdminErrorCode.PRODUCT_INVALID_STATUS);
		}
		return product;
	}
}
