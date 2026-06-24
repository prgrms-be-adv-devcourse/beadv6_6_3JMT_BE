package com.prompthub.product.application.service;

import com.prompthub.product.application.client.SellerClient;
import com.prompthub.product.application.client.SellerInfo;
import com.prompthub.product.application.usecase.ProductQueryUseCase;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.model.projection.ProductListProjection;
import com.prompthub.product.domain.model.projection.ProductReviewProjection;
import com.prompthub.product.domain.repository.ProductRepository;
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.exception.enums.ProductErrorCode;
import com.prompthub.product.presentation.dto.response.ProductDetailResponse;
import com.prompthub.product.presentation.dto.response.ProductListItemResponse;
import com.prompthub.product.presentation.dto.response.ProductReviewResponse;
import com.prompthub.product.presentation.dto.response.ProductVersionResponse;
import com.prompthub.presentation.dto.PageResponse;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductQueryService implements ProductQueryUseCase {

	private static final String DEFAULT_CATEGORY = "all";
	private static final int DEFAULT_LIMIT = 4;

	private final ProductRepository productRepository;
	private final SellerClient sellerClient;

	public PageResponse<ProductListItemResponse> getProducts(
		String q,
		String category,
		String sort,
		int page,
		int size
	) {
		int normalizedPage = normalizePositive(page);
		int normalizedSize = normalizePositive(size);
		String keyword = normalizeKeyword(q);
		String selectedCategory = normalizeCategory(category);
		String selectedSort = normalizeSort(sort);

		List<ProductListProjection> products = productRepository.findPublicProducts(
			keyword,
			selectedCategory,
			selectedSort,
			PageRequest.of(normalizedPage - 1, normalizedSize)
		);
		long total = productRepository.countPublicProducts(keyword, selectedCategory);
		boolean hasNext = (long) (normalizedPage - 1) * normalizedSize + products.size() < total;

		Map<UUID, String> sellerNames = products.stream()
			.map(ProductListProjection::sellerId)
			.distinct()
			.collect(Collectors.toMap(id -> id, id -> sellerClient.getSellerInfo(id).sellerName()));

		return PageResponse.success(
			products.stream()
				.map(p -> toListItemResponse(p, sellerNames.getOrDefault(p.sellerId(), "")))
				.toList(),
			normalizedPage,
			normalizedSize,
			total,
			hasNext
		);
	}

	public ProductDetailResponse getProduct(UUID productId) {
		Product product = getOnSaleProduct(productId);
		double rating = productRepository.getAverageRating(productId);
		SellerInfo seller = sellerClient.getSellerInfo(product.getSellerId());

		return new ProductDetailResponse(
			product.getId(),
			product.getName(),
			resolveCategory(product),
			resolveIcon(product),
			product.getProductType(),
			product.getAmount(),
			rating,
			product.getSalesCount(),
			seller.sellerName(),
			product.getSellerId(),
			null,
			product.getDescription(),
			product.getThumbnailUrl(),
			createPreviewContent(product),
			List.of(toVersionResponse(product)),
			List.of(),
			product.getCreatedAt(),
			product.getUpdatedAt()
		);
	}

	public List<ProductListItemResponse> getRelatedProducts(UUID productId, int limit) {
		Product product = getOnSaleProduct(productId);
		int normalizedLimit = limit > 0 ? limit : DEFAULT_LIMIT;

		List<ProductListProjection> related = productRepository.findRelatedProducts(
			product.getId(), product.getCategoryId(), normalizedLimit);

		Map<UUID, String> sellerNames = related.stream()
			.map(ProductListProjection::sellerId)
			.distinct()
			.collect(Collectors.toMap(id -> id, id -> sellerClient.getSellerInfo(id).sellerName()));

		return related.stream()
			.map(p -> toListItemResponse(p, sellerNames.getOrDefault(p.sellerId(), "")))
			.toList();
	}

	public List<ProductReviewResponse> getProductReviews(UUID productId) {
		getOnSaleProduct(productId);

		return productRepository.findActiveReviews(productId)
			.stream()
			.map(this::toReviewResponse)
			.toList();
	}

	private Product getOnSaleProduct(UUID productId) {
		Product product = productRepository.findById(productId)
			.orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));

		if (product.getStatus() != ProductStatus.ON_SALE || product.getDeletedAt() != null) {
			throw new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND);
		}

		return product;
	}

	private ProductListItemResponse toListItemResponse(ProductListProjection product, String sellerName) {
		return new ProductListItemResponse(
			product.id(),
			product.title(),
			product.category(),
			resolveIcon(product.categoryIcon()),
			product.model(),
			product.amount(),
			null,
			product.rating(),
			product.salesCount(),
			sellerName,
			product.sellerId(),
			null,
			product.description(),
			product.thumbnailUrl(),
			product.createdAt(),
			product.updatedAt()
		);
	}

	private ProductReviewResponse toReviewResponse(ProductReviewProjection review) {
		return new ProductReviewResponse(
			review.id(),
			review.userId(),
			review.rating(),
			review.content(),
			review.createdAt(),
			review.updatedAt()
		);
	}

	private ProductVersionResponse toVersionResponse(Product product) {
		return new ProductVersionResponse(
			"v" + product.getMajorVersion() + "." + product.getPatchVersion(),
			product.getUpdatedAt().toLocalDate().toString(),
			product.getChangeReason()
		);
	}

	private String createPreviewContent(Product product) {
		return "[" + product.getName() + "]\n\n전체 내용은 구매 후 확인할 수 있습니다.";
	}

	private String resolveCategory(Product product) {
		if (product.getCategory() == null || product.getCategory().getName() == null) {
			return "";
		}

		return product.getCategory().getName();
	}

	private String resolveIcon(Product product) {
		if (product.getCategory() == null || product.getCategory().getIcon() == null
			|| product.getCategory().getIcon().isBlank()) {
			return "sparkles";
		}

		return product.getCategory().getIcon();
	}

	private String resolveIcon(String categoryCode) {
		if (categoryCode == null || categoryCode.isBlank()) {
			return "sparkles";
		}

		return categoryCode;
	}

	private int normalizePositive(int value) {
		return Math.max(value, 1);
	}

	private String normalizeKeyword(String q) {
		if (q == null || q.isBlank()) {
			return "";
		}

		return q.trim().toLowerCase(Locale.ROOT);
	}

	private String normalizeCategory(String category) {
		if (category == null || category.isBlank()) {
			return DEFAULT_CATEGORY;
		}

		return category;
	}

	private String normalizeSort(String sort) {
		if ("rating".equals(sort) || "price-asc".equals(sort) || "price-desc".equals(sort)) {
			return sort;
		}

		return "popular";
	}
}
