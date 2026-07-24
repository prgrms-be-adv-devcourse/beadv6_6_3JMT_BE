package com.prompthub.product.application.service;

import com.prompthub.product.application.client.StorageClient;
import com.prompthub.product.application.usecase.ProductQueryUseCase;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.entity.ProductFamily;
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
import com.prompthub.product.presentation.dto.response.ProductsByIdsResponse;
import com.prompthub.presentation.dto.PageResponse;
import com.prompthub.search.application.ProductSearchHit;
import com.prompthub.search.application.ProductSearchPageResult;
import com.prompthub.search.application.ProductSearchQueryService;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductQueryService implements ProductQueryUseCase {

	private static final String ALL_PRODUCT_TYPES = "all";
	private static final int DEFAULT_LIMIT = 4;

	private final ProductRepository productRepository;
	private final StorageClient storageClient;
	private final ProductFamilyResolver productFamilyResolver;
	private final ProductSearchQueryService productSearchQueryService;

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

		try {
			return searchViaElasticsearch(keyword, selectedProductType, selectedSort, normalizedPage, normalizedSize);
		} catch (RuntimeException e) {
			log.warn("ES 조회에 실패해 RDB로 폴백합니다.", e);
			return searchViaRdb(keyword, selectedProductType, selectedSort, normalizedPage, normalizedSize);
		}
	}

	private PageResponse<ProductListItemResponse> searchViaElasticsearch(
		String keyword, String productType, String sort, int page, int size
	) {
		ProductSearchPageResult result = productSearchQueryService.search(keyword, productType, sort, page, size);
		boolean hasNext = (long) (page - 1) * size + result.hits().size() < result.total();

		return PageResponse.success(
			result.hits().stream().map(this::toListItemResponse).toList(),
			page,
			size,
			result.total(),
			hasNext
		);
	}

	private PageResponse<ProductListItemResponse> searchViaRdb(
		String keyword, String productType, String sort, int page, int size
	) {
		List<ProductListProjection> products = productRepository.findPublicProducts(
			keyword,
			productType,
			sort,
			PageRequest.of(page - 1, size)
		);
		long total = productRepository.countPublicProducts(keyword, productType);
		boolean hasNext = (long) (page - 1) * size + products.size() < total;

		Map<UUID, List<String>> tagsByProductId = productRepository
			.findAllByIdIn(products.stream().map(ProductListProjection::id).toList())
			.stream()
			.collect(Collectors.toMap(Product::getId, Product::getTags));

		return PageResponse.success(
			products.stream()
				.map(p -> toListItemResponse(p, tagsByProductId.getOrDefault(p.id(), List.of())))
				.toList(),
			page,
			size,
			total,
			hasNext
		);
	}

	@Transactional
	public ProductDetailResponse getProduct(UUID productId) {
		Product product = getOnSaleProduct(productId);
		product.incrementViewCount();
		productRepository.save(product);
		double rating = productRepository.getAverageRating(product.familyRootId());
		int sellerProductCount = (int) productRepository.countOnSaleProductsBySellerId(product.getSellerId());

		return new ProductDetailResponse(
			product.getId(),
			product.getName(),
			product.getProductType().name(),
			product.getModel(),
			product.getAmount(),
			rating,
			(int) productRepository.sumSalesCountByFamilyRootId(product.familyRootId()),
			product.getSellerId(),
			sellerProductCount,
			null,
			product.getDescription(),
			toUrl(product.getThumbnailUrl()),
			toUrls(product.getImageUrls()),
			createPreviewContent(product),
			product.getTags(),
			toVersionHistory(product.familyRootId()),
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

		Map<UUID, List<String>> tagsByProductId = productRepository
			.findAllByIdIn(related.stream().map(ProductListProjection::id).toList())
			.stream()
			.collect(Collectors.toMap(Product::getId, Product::getTags));

		return related.stream()
			.map(p -> toListItemResponse(p, tagsByProductId.getOrDefault(p.id(), List.of())))
			.toList();
	}

	public List<ProductReviewResponse> getProductReviews(UUID productId) {
		Product product = getOnSaleProduct(productId);

		return productRepository.findActiveReviews(product.familyRootId())
			.stream()
			.map(this::toReviewResponse)
			.toList();
	}

	@Override
	public List<ProductsByIdsResponse> getProductsByIds(List<UUID> productIds) {
		Map<UUID, Product> resolved = productFamilyResolver.resolveFamilyRepresentatives(productIds, ProductFamily::currentForWishlist);
		return productIds.stream()
			.filter(resolved::containsKey)
			.map(id -> {
				Product p = resolved.get(id);
				return new ProductsByIdsResponse(
					id,
					p.getSellerId(),
					p.getName(),
					p.getAmount(),
					toUrl(p.getThumbnailUrl()),
					p.getProductType().name(),
					p.getModel() != null ? p.getModel() : "",
					(int) productRepository.sumSalesCountByFamilyRootId(p.familyRootId()),
					productRepository.getAverageRating(p.familyRootId()),
					p.getStatus().name()
				);
			})
			.toList();
	}

	private Product getOnSaleProduct(UUID productId) {
		Product anchor = productRepository.findById(productId)
			.orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));
		UUID familyRootId = anchor.familyRootId();
		List<Product> members = productRepository.findAllByFamilyRootIds(List.of(familyRootId));
		ProductFamily family = ProductFamily.of(familyRootId, members);
		return family.currentOnSale()
			.filter(p -> p.getDeletedAt() == null)
			.orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));
	}

	private List<ProductVersionResponse> toVersionHistory(UUID familyRootId) {
		List<Product> members = productRepository.findAllByFamilyRootIds(List.of(familyRootId));
		return ProductFamily.of(familyRootId, members).publicHistory().stream()
			.map(this::toVersionResponse)
			.toList();
	}

	private ProductListItemResponse toListItemResponse(ProductListProjection product, List<String> tags) {
		return new ProductListItemResponse(
			product.id(),
			product.title(),
			product.productType(),
			product.model(),
			product.amount(),
			null,
			product.rating(),
			product.salesCount(),
			product.sellerId(),
			null,
			product.description(),
			toUrl(product.thumbnailUrl()),
			tags,
			product.createdAt(),
			product.updatedAt()
		);
	}

	private ProductListItemResponse toListItemResponse(ProductSearchHit hit) {
		return new ProductListItemResponse(
			hit.productId(),
			hit.name(),
			hit.productType(),
			hit.model(),
			hit.amount(),
			null,
			hit.ratingAvg(),
			hit.salesCount(),
			hit.sellerId(),
			null,
			hit.description(),
			toUrl(hit.thumbnailUrl()),
			hit.tags(),
			hit.firstPublishedAt(),
			hit.currentVersionAt()
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

	private String toUrl(String key) {
		if (key == null || key.isBlank()) {
			return null;
		}
		return storageClient.generatePresignedDownloadUrl(key);
	}

	private List<String> toUrls(List<String> keys) {
		if (keys == null || keys.isEmpty()) {
			return List.of();
		}
		return keys.stream()
			.map(storageClient::generatePresignedDownloadUrl)
			.toList();
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
		if ("rating".equals(sort) || "price-asc".equals(sort)) {
			return sort;
		}

		return "popular";
	}
}
