package com.prompthub.product.application.service.query;

import com.prompthub.product.application.gateway.external.ObjectStorageGateway;
import com.prompthub.product.application.usecase.query.ProductSearchUseCase;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.ProductType;
import com.prompthub.product.domain.model.projection.ProductListProjection;
import com.prompthub.product.domain.repository.ProductRepository;
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.exception.enums.ProductErrorCode;
import com.prompthub.product.presentation.dto.response.product.ProductListItemResponse;
import com.prompthub.presentation.dto.PageResponse;
import com.prompthub.search.application.query.ProductSearchHit;
import com.prompthub.search.application.query.ProductSearchPageResult;
import com.prompthub.search.application.query.ProductSearchQueryPort;
import com.prompthub.search.application.query.ProductSearchUnavailableException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductSearchService implements ProductSearchUseCase {

	private static final String ALL_PRODUCT_TYPES = "all";
	private static final int SUGGEST_LIMIT = 5;

	private final ProductRepository productRepository;
	private final ObjectStorageGateway objectStorage;
	private final ProductSearchQueryPort productSearchQueryPort;

	@Override
	public PageResponse<ProductListItemResponse> getProducts(
		String q,
		String productType,
		String sort,
		int page,
		int size
	) {
		String keyword = normalizeKeyword(q);
		String selectedProductType = normalizeProductType(productType);
		String selectedSort = normalizeSort(sort);
		Pageable pageable = PageRequest.of(normalizePage(page), normalizePositive(size));

		try {
			return searchProductsWithElasticsearch(keyword, selectedProductType, selectedSort, pageable);
		} catch (ProductSearchUnavailableException exception) {
			log.warn("ES 상품 검색을 사용할 수 없어 RDB로 폴백합니다.", exception);
			return searchProductsWithRdb(keyword, selectedProductType, selectedSort, pageable);
		}
	}

	@Override
	public List<String> suggest(String q) {
		String keyword = normalizeKeyword(q);
		if (keyword.isBlank()) {
			return List.of();
		}

		try {
			return productSearchQueryPort.suggest(keyword, SUGGEST_LIMIT);
		} catch (ProductSearchUnavailableException exception) {
			log.warn("ES 상품명 자동완성을 사용할 수 없어 빈 목록을 반환합니다.", exception);
			return List.of();
		}
	}

	private PageResponse<ProductListItemResponse> searchProductsWithElasticsearch(
		String keyword, String productType, String sort, Pageable pageable
	) {
		ProductSearchPageResult result = productSearchQueryPort.search(keyword, productType, sort, pageable);
		boolean hasNext = pageable.getOffset() + result.hits().size() < result.total();

		return PageResponse.success(
			result.hits().stream().map(this::toListItemResponse).toList(),
			pageable.getPageNumber(),
			pageable.getPageSize(),
			result.total(),
			hasNext
		);
	}

	private PageResponse<ProductListItemResponse> searchProductsWithRdb(
		String keyword, String productType, String sort, Pageable pageable
	) {
		List<ProductListProjection> products = productRepository.findPublicProducts(keyword, productType, sort, pageable);
		long total = productRepository.countPublicProducts(keyword, productType);
		boolean hasNext = pageable.getOffset() + products.size() < total;

		Map<UUID, List<String>> tagsByProductId = productRepository
			.findAllByIdIn(products.stream().map(ProductListProjection::id).toList())
			.stream()
			.collect(Collectors.toMap(Product::getId, Product::getTags));

		return PageResponse.success(
			products.stream()
				.map(product -> ProductListItemResponse.from(
					product,
					objectStorage.presignIfPresent(product.thumbnailUrl()),
					tagsByProductId.getOrDefault(product.id(), List.of())))
				.toList(),
			pageable.getPageNumber(),
			pageable.getPageSize(),
			total,
			hasNext
		);
	}

	private ProductListItemResponse toListItemResponse(ProductSearchHit hit) {
		return new ProductListItemResponse(
			hit.productId(), hit.name(), hit.productType(), hit.model(), hit.amount(),
			hit.ratingAvg(), hit.salesCount(), hit.sellerId(), hit.description(),
			objectStorage.presignIfPresent(hit.thumbnailUrl()), hit.tags(), hit.firstPublishedAt(), hit.currentVersionAt()
		);
	}

	private int normalizePage(int value) {
		return Math.max(value, 0);
	}

	private int normalizePositive(int value) {
		return Math.max(value, 1);
	}

	private String normalizeKeyword(String keyword) {
		if (keyword == null || keyword.isBlank()) {
			return "";
		}
		return keyword.trim().toLowerCase(Locale.ROOT);
	}

	private String normalizeProductType(String productType) {
		if (productType == null || productType.isBlank() || ALL_PRODUCT_TYPES.equalsIgnoreCase(productType)) {
			return ALL_PRODUCT_TYPES;
		}
		try {
			return ProductType.valueOf(productType.toUpperCase(Locale.ROOT)).name();
		} catch (IllegalArgumentException exception) {
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
