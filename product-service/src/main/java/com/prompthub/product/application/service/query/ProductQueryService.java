package com.prompthub.product.application.service.query;

import com.prompthub.product.application.gateway.external.ObjectStorageGateway;
import com.prompthub.product.application.usecase.query.ProductQueryUseCase;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.entity.ProductFamily;
import com.prompthub.product.domain.model.projection.ProductListProjection;
import com.prompthub.product.domain.model.projection.ProductReviewProjection;
import com.prompthub.product.domain.repository.ProductRepository;
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.exception.enums.ProductErrorCode;
import com.prompthub.product.presentation.dto.response.product.ProductDetailResponse;
import com.prompthub.product.presentation.dto.response.product.ProductListItemResponse;
import com.prompthub.product.presentation.dto.response.review.ProductReviewResponse;
import com.prompthub.product.presentation.dto.response.product.ProductVersionResponse;
import com.prompthub.product.presentation.dto.response.product.ProductsByIdsResponse;
import com.prompthub.recommendation.application.ProductRecommender;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductQueryService implements ProductQueryUseCase {

	private static final int DEFAULT_LIMIT = 4;

	private final ProductRepository productRepository;
	private final ObjectStorageGateway objectStorage;
	private final ProductFamilyResolver productFamilyResolver;
	private final ProductRecommender productRecommender;

	@Transactional
	public ProductDetailResponse getProduct(UUID productId) {
		Product product = findCurrentOnSaleProduct(productId);
		LocalDateTime viewedAt = LocalDateTime.now();
		if (!productRepository.incrementViewCount(product.getId(), viewedAt)) {
			throw new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND);
		}
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
			objectStorage.presignIfPresent(product.getThumbnailUrl()),
			objectStorage.presignAllIfPresent(product.getImageUrls()),
			createPreviewContent(product),
			product.getTags(),
			getPublicVersionHistory(product.familyRootId()),
			List.of(),
			product.isHasContext(),
			product.isHasObjective(),
			product.isHasNuance(),
			product.isHasTone(),
			product.isHasExamples(),
			product.isHasExecution(),
			product.isHasRoleAssignment(),
			product.isChecklistRecorded(),
			product.getCreatedAt(),
			viewedAt
		);
	}

	public List<ProductListItemResponse> getRecommendedProducts(UUID productId, int limit) {
		Product product = findCurrentOnSaleProduct(productId);
		int normalizedLimit = limit > 0 ? limit : DEFAULT_LIMIT;

		List<UUID> recommendedIds = productRecommender.recommend(
			product.getId(), product.familyRootId(), product.getProductType().name(), normalizedLimit);
		if (recommendedIds.isEmpty()) {
			return List.of();
		}

		// 조회는 순서를 보장하지 않는다. 추천 순위대로 다시 세우지 않으면 어렵게 계산한
		// 순서가 DB가 돌려준 순서로 덮인다.
		Map<UUID, ProductListProjection> byId = productRepository.findProjectionsByIds(recommendedIds).stream()
			.collect(Collectors.toMap(ProductListProjection::id, p -> p));
		Map<UUID, List<String>> tagsByProductId = productRepository.findAllByIdIn(recommendedIds).stream()
			.collect(Collectors.toMap(Product::getId, Product::getTags));

		return recommendedIds.stream()
			.map(byId::get)
			.filter(Objects::nonNull)
			.map(projection -> ProductListItemResponse.from(
				projection,
				objectStorage.presignIfPresent(projection.thumbnailUrl()),
				tagsByProductId.getOrDefault(projection.id(), List.of())))
			.toList();
	}

	public List<ProductReviewResponse> getProductReviews(UUID productId) {
		Product product = findCurrentOnSaleProduct(productId);

		return productRepository.findActiveReviews(product.familyRootId())
			.stream()
			.map(this::toReviewResponse)
			.toList();
	}

	@Override
	public List<ProductsByIdsResponse> getProductsByIds(List<UUID> productIds) {
		Map<UUID, Product> resolved = productFamilyResolver.resolveFamilyRepresentatives(productIds, ProductFamily::currentForWishlist);
		List<UUID> familyRootIds = resolved.values().stream()
			.map(Product::familyRootId)
			.collect(Collectors.collectingAndThen(
				Collectors.toCollection(LinkedHashSet::new),
				List::copyOf
			));
		Map<UUID, Long> salesCounts = productRepository.getSalesCounts(familyRootIds);
		Map<UUID, Double> averageRatings = productRepository.getAverageRatings(familyRootIds);

		return productIds.stream()
			.filter(resolved::containsKey)
			.map(id -> {
				Product p = resolved.get(id);
				return new ProductsByIdsResponse(
					id,
					p.getSellerId(),
					p.getName(),
					p.getAmount(),
					objectStorage.presignIfPresent(p.getThumbnailUrl()),
					p.getProductType().name(),
					p.getModel() != null ? p.getModel() : "",
					Math.toIntExact(salesCounts.getOrDefault(p.familyRootId(), 0L)),
					averageRatings.getOrDefault(p.familyRootId(), 0.0),
					p.getStatus().name()
				);
			})
			.toList();
	}

	private Product findCurrentOnSaleProduct(UUID productId) {
		Product anchor = productRepository.findById(productId)
			.orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));
		UUID familyRootId = anchor.familyRootId();
		List<Product> members = productRepository.findAllByFamilyRootIds(List.of(familyRootId));
		ProductFamily family = ProductFamily.of(familyRootId, members);
		return family.currentOnSale()
			.filter(p -> p.getDeletedAt() == null)
			.orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));
	}

	private List<ProductVersionResponse> getPublicVersionHistory(UUID familyRootId) {
		List<Product> members = productRepository.findAllByFamilyRootIds(List.of(familyRootId));
		return ProductFamily.of(familyRootId, members).publicHistory().stream()
			.map(this::toVersionResponse)
			.toList();
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

}
