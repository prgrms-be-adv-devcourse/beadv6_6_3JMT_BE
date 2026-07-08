package com.prompthub.product.application.service;

import com.prompthub.product.application.client.SellerClient;
import com.prompthub.product.application.client.SellerInfo;
import com.prompthub.product.application.usecase.ProductQueryUseCase;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.model.enums.ProductType;
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

	private static final String ALL_PRODUCT_TYPES = "all";
	private static final int DEFAULT_LIMIT = 4;

	private final ProductRepository productRepository;
	private final SellerClient sellerClient;

	public PageResponse<ProductListItemResponse> getProducts(
		String q,
		String productType,
		String sort,
		int page,
		int size
	) {
		int normalizedPage = normalizePositive(page);
		int normalizedSize = normalizePositive(size);
		String keyword = normalizeKeyword(q);
		String selectedProductType = normalizeProductType(productType);
		String selectedSort = normalizeSort(sort);

		List<ProductListProjection> products = productRepository.findPublicProducts(
			keyword,
			selectedProductType,
			selectedSort,
			PageRequest.of(normalizedPage - 1, normalizedSize)
		);
		long total = productRepository.countPublicProducts(keyword, selectedProductType);
		boolean hasNext = (long) (normalizedPage - 1) * normalizedSize + products.size() < total;

		Map<UUID, String> sellerNames = products.stream()
			.map(ProductListProjection::sellerId)
			.distinct()
			.collect(Collectors.toMap(id -> id, id -> sellerClient.getSellerInfo(id).sellerName()));
		Map<UUID, List<String>> tagsByProductId = productRepository
			.findAllByIdIn(products.stream().map(ProductListProjection::id).toList())
			.stream()
			.collect(Collectors.toMap(Product::getId, Product::getTags));

		return PageResponse.success(
			products.stream()
				.map(p -> toListItemResponse(
					p,
					sellerNames.getOrDefault(p.sellerId(), ""),
					tagsByProductId.getOrDefault(p.id(), List.of())
				))
				.toList(),
			normalizedPage,
			normalizedSize,
			total,
			hasNext
		);
	}

	@Transactional
	public ProductDetailResponse getProduct(UUID productId) {
		Product product = getOnSaleProduct(productId);
		product.incrementViewCount();
		productRepository.save(product);
		double rating = productRepository.getAverageRating(productId);
		SellerInfo seller = sellerClient.getSellerInfo(product.getSellerId());
		int sellerProductCount = (int) productRepository.countOnSaleProductsBySellerId(product.getSellerId());

		return new ProductDetailResponse(
			product.getId(),
			product.getName(),
			product.getProductType().name(),
			product.getModel(),
			product.getAmount(),
			rating,
			product.getSalesCount(),
			seller.sellerName(),
			product.getSellerId(),
			seller.profileImageUrl(),
			sellerProductCount,
			null,
			product.getDescription(),
			product.getThumbnailUrl(),
			createPreviewContent(product),
			product.getTags(),
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
			product.getId(), product.getProductType(), normalizedLimit);

		Map<UUID, String> sellerNames = related.stream()
			.map(ProductListProjection::sellerId)
			.distinct()
			.collect(Collectors.toMap(id -> id, id -> sellerClient.getSellerInfo(id).sellerName()));
		Map<UUID, List<String>> tagsByProductId = productRepository
			.findAllByIdIn(related.stream().map(ProductListProjection::id).toList())
			.stream()
			.collect(Collectors.toMap(Product::getId, Product::getTags));

		return related.stream()
			.map(p -> toListItemResponse(
				p,
				sellerNames.getOrDefault(p.sellerId(), ""),
				tagsByProductId.getOrDefault(p.id(), List.of())
			))
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

	private ProductListItemResponse toListItemResponse(ProductListProjection product, String sellerName, List<String> tags) {
		return new ProductListItemResponse(
			product.id(),
			product.title(),
			product.productType(),
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
			tags,
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

	private int normalizePositive(int value) {
		return Math.max(value, 1);
	}

	private String normalizeKeyword(String q) {
		if (q == null || q.isBlank()) {
			return "";
		}

		return q.trim().toLowerCase(Locale.ROOT);
	}

	private String normalizeProductType(String productType) {
		if (productType == null || productType.isBlank() || ALL_PRODUCT_TYPES.equalsIgnoreCase(productType)) {
			return ALL_PRODUCT_TYPES;
		}

		try {
			return ProductType.valueOf(productType.toUpperCase(Locale.ROOT)).name();
		} catch (IllegalArgumentException e) {
			throw new ProductException(ProductErrorCode.INVALID_PRODUCT_TYPE);
		}
	}

	private String normalizeSort(String sort) {
		if ("rating".equals(sort) || "price-asc".equals(sort) || "price-desc".equals(sort)) {
			return sort;
		}

		return "popular";
	}
}
