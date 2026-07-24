package com.prompthub.admin.product.application.service;

import com.prompthub.admin.global.exception.AdminErrorCode;
import com.prompthub.admin.order.domain.model.SellerNickname;
import com.prompthub.admin.order.infrastructure.persistence.SellerNicknameRepository;
import com.prompthub.admin.product.application.dto.AdminProductListQuery;
import com.prompthub.admin.product.application.dto.AdminProductPageResult;
import com.prompthub.admin.product.application.usecase.ProductUseCase;
import com.prompthub.admin.product.domain.exception.ProductException;
import com.prompthub.admin.product.domain.model.entity.Product;
import com.prompthub.admin.product.domain.model.entity.ProductFamily;
import com.prompthub.admin.product.domain.model.enums.ProductStatus;
import com.prompthub.admin.product.domain.repository.ProductRepository;
import com.prompthub.admin.product.presentation.dto.response.AdminProductListItemResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class ProductService implements ProductUseCase {

	private static final String UNKNOWN_SELLER_NICKNAME = "알 수 없음";

	private final ProductRepository productRepository;
	private final SellerNicknameRepository sellerNicknameRepository;

	@Override
	@Transactional(readOnly = true)
	public AdminProductPageResult listProducts(AdminProductListQuery query) {
		String keyword = normalizeKeyword(query.keyword());
		List<UUID> keywordSellerIds = keyword == null
			? List.of()
			: sellerNicknameRepository.findByNicknameContainingIgnoreCase(keyword).stream()
				.map(SellerNickname::getSellerId)
				.toList();

		// page는 0부터 시작 (FE DataPagination과 동일한 0-base 계약)
		List<Product> products = productRepository.findProducts(
			query.status(), keyword, keywordSellerIds, query.page(), query.size());
		long total = productRepository.countProducts(query.status(), keyword, keywordSellerIds);

		List<AdminProductListItemResponse> items = toListItemResponses(products);
		boolean hasNext = total > (long) (query.page() + 1) * query.size();

		return new AdminProductPageResult(items, query.page(), query.size(), total, hasNext);
	}

	private static String normalizeKeyword(String keyword) {
		if (keyword == null) return null;
		String trimmed = keyword.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private List<AdminProductListItemResponse> toListItemResponses(List<Product> products) {
		List<UUID> sellerIds = products.stream()
			.map(Product::getSellerId)
			.distinct()
			.toList();
		Map<UUID, String> sellerNicknames = sellerIds.isEmpty()
			? Map.of()
			: sellerNicknameRepository.findAllById(sellerIds).stream()
				.collect(Collectors.toMap(
					SellerNickname::getSellerId,
					SellerNickname::getNickname,
					(existing, ignored) -> existing
				));

		return products.stream()
			.map(product -> AdminProductListItemResponse.from(
				product,
				sellerNicknames.getOrDefault(product.getSellerId(), UNKNOWN_SELLER_NICKNAME)
			))
			.toList();
	}

	@Override
	public void approveProduct(UUID productId) {
		Product target = getProductInPendingReview(productId);
		UUID familyRootId = target.familyRootId();
		ProductFamily family = ProductFamily.of(
			familyRootId,
			productRepository.findAllByFamilyRootIds(List.of(familyRootId))
		);
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
			.orElseThrow(() -> new ProductException(AdminErrorCode.PRODUCT_NOT_FOUND));
		if (target.getStatus() != ProductStatus.ON_SALE && target.getStatus() != ProductStatus.REJECTED) {
			throw new ProductException(AdminErrorCode.PRODUCT_INVALID_STATUS);
		}

		boolean wasOnSale = target.getStatus() == ProductStatus.ON_SALE;
		UUID familyRootId = target.familyRootId();

		if (wasOnSale) {
			ProductFamily family = ProductFamily.of(
				familyRootId,
				productRepository.findAllByFamilyRootIds(List.of(familyRootId))
			);
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
			.orElseThrow(() -> new ProductException(AdminErrorCode.PRODUCT_NOT_FOUND));
		if (product.getStatus() != ProductStatus.PENDING_REVIEW) {
			throw new ProductException(AdminErrorCode.PRODUCT_INVALID_STATUS);
		}
		return product;
	}
}
