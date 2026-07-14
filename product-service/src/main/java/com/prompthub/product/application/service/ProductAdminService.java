package com.prompthub.product.application.service;

import com.prompthub.product.application.usecase.ProductAdminUseCase;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.entity.ProductFamily;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.repository.ProductRepository;
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.exception.enums.ProductErrorCode;
import com.prompthub.product.presentation.dto.response.AdminProductListItemResponse;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class ProductAdminService implements ProductAdminUseCase {

	private final ProductRepository productRepository;

	@Override
	@Transactional(readOnly = true)
	public List<AdminProductListItemResponse> getPendingReviewProducts() {
		return productRepository.findAllAdminProducts().stream()
			.map(AdminProductListItemResponse::from)
			.toList();
	}

	@Override
	public void approveProduct(UUID productId) {
		Product target = getProductInPendingReview(productId);
		UUID familyRootId = target.familyRootId();
		ProductFamily family = ProductFamily.of(familyRootId, productRepository.findAllByFamilyRootIds(List.of(familyRootId)));
		family.currentOnSale().ifPresent(previous -> {
			previous.supersede();
			productRepository.save(previous);
		});
		target.approve();
		productRepository.save(target);
	}

	@Override
	public void rejectProduct(UUID productId, String reason) {
		Product product = getProductInPendingReview(productId);
		product.reject(reason);
		productRepository.save(product);
	}

	@Override
	public void revertProductToPendingReview(UUID productId) {
		Product target = productRepository.findById(productId)
			.orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));
		if (target.getStatus() != ProductStatus.ON_SALE && target.getStatus() != ProductStatus.REJECTED) {
			throw new ProductException(ProductErrorCode.PRODUCT_INVALID_STATUS);
		}

		if (target.getStatus() == ProductStatus.ON_SALE) {
			UUID familyRootId = target.familyRootId();
			ProductFamily family = ProductFamily.of(familyRootId, productRepository.findAllByFamilyRootIds(List.of(familyRootId)));
			family.mostRecentSuperseded().ifPresent(paired -> {
				paired.restoreFromSuperseded();
				productRepository.save(paired);
			});
		}

		target.revertToPendingReview();
		productRepository.save(target);
	}

	private Product getProductInPendingReview(UUID productId) {
		Product product = productRepository.findById(productId)
			.orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));
		if (product.getStatus() != ProductStatus.PENDING_REVIEW) {
			throw new ProductException(ProductErrorCode.PRODUCT_INVALID_STATUS);
		}
		return product;
	}
}
