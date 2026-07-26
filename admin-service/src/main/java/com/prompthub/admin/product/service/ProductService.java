package com.prompthub.admin.product.service;

import com.prompthub.admin.global.exception.AdminErrorCode;
import com.prompthub.admin.product.dto.AdminProductListQuery;
import com.prompthub.admin.product.dto.AdminProductPageResult;
import com.prompthub.admin.product.exception.ProductException;
import com.prompthub.admin.product.dto.ProductListFilter;
import com.prompthub.admin.product.entity.Product;
import com.prompthub.admin.product.model.ProductFamily;
import com.prompthub.admin.product.entity.enums.ProductStatus;
import com.prompthub.admin.product.repository.ProductRepository;
import com.prompthub.admin.product.dto.response.AdminProductListItemResponse;
import com.prompthub.admin.user.service.UserService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class ProductService {

	private static final String UNKNOWN_SELLER_NICKNAME = "알 수 없음";

	private final ProductRepository productRepository;
	private final UserService userService;

	@Transactional(readOnly = true)
	public AdminProductPageResult listProducts(AdminProductListQuery query) {
		String keyword = normalizeKeyword(query.keyword());
		List<UUID> keywordSellerIds = keyword == null
			? List.of()
			: userService.findIdsByNameContainingIgnoreCase(keyword);

		Page<Product> page = productRepository.findProducts(
			new ProductListFilter(query.status(), keyword, keywordSellerIds), query.pageable());

		return new AdminProductPageResult(
			toListItemResponses(page.getContent()),
			query.pageable().getPageNumber(),
			query.pageable().getPageSize(),
			page.getTotalElements(),
			page.hasNext()
		);
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
			: userService.findNamesByIds(sellerIds);

		return products.stream()
			.map(product -> AdminProductListItemResponse.from(
				product,
				sellerNicknames.getOrDefault(product.getSellerId(), UNKNOWN_SELLER_NICKNAME)
			))
			.toList();
	}

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

	public void rejectProduct(UUID productId, String reason) {
		Product product = getProductInPendingReview(productId);
		product.reject(reason);
		productRepository.save(product);
	}

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
