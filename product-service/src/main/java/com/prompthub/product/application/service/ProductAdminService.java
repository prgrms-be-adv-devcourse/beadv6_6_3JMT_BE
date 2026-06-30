package com.prompthub.product.application.service;

import com.prompthub.product.application.usecase.ProductAdminUseCase;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.repository.ProductRepository;
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.exception.enums.ProductErrorCode;
import com.prompthub.product.presentation.dto.response.AdminProductListItemResponse;
import java.util.Arrays;
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
	public List<AdminProductListItemResponse> getPendingReviewProducts(String role) {
		validateAdmin(role);
		return productRepository.findAllAdminProducts().stream()
			.map(AdminProductListItemResponse::from)
			.toList();
	}

	@Override
	public void approveProduct(String role, UUID productId) {
		validateAdmin(role);
		Product product = getProductInPendingReview(productId);
		product.approve();
		productRepository.save(product);
	}

	@Override
	public void rejectProduct(String role, UUID productId, String reason) {
		validateAdmin(role);
		Product product = getProductInPendingReview(productId);
		product.reject(reason);
		productRepository.save(product);
	}

	@Override
	public void revertProductToPendingReview(String role, UUID productId) {
		validateAdmin(role);
		Product product = productRepository.findById(productId)
			.orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));
		if (product.getStatus() != ProductStatus.ON_SALE && product.getStatus() != ProductStatus.REJECTED) {
			throw new ProductException(ProductErrorCode.PRODUCT_INVALID_STATUS);
		}
		product.revertToPendingReview();
		productRepository.save(product);
	}

	private void validateAdmin(String role) {
		if (Arrays.stream(role.split(",")).noneMatch("ADMIN"::equals)) {
			throw new ProductException(ProductErrorCode.PRODUCT_FORBIDDEN);
		}
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
