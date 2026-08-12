package com.prompthub.product.application.service.seller;

import com.prompthub.product.application.gateway.external.ObjectStorageGateway;
import com.prompthub.product.application.service.fileupload.TempFilePromoter;
import com.prompthub.product.application.usecase.seller.ProductSellerUseCase;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.entity.ProductFamily;
import com.prompthub.product.domain.model.enums.AmountType;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.model.enums.ProductType;
import com.prompthub.product.domain.model.enums.ProductVersionType;
import com.prompthub.product.domain.model.vo.ProductContent;
import com.prompthub.product.domain.repository.ProductRepository;
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.exception.enums.ProductErrorCode;
import com.prompthub.product.infra.messaging.producer.ProductEventProducer;
import com.prompthub.product.presentation.dto.request.product.ProductCreateRequest;
import com.prompthub.product.presentation.dto.request.product.ProductUpdateRequest;
import com.prompthub.product.presentation.dto.response.product.ProductCountResponse;
import com.prompthub.product.presentation.dto.response.product.ProductCreateResponse;
import com.prompthub.product.presentation.dto.response.product.ProductUpdateResponse;
import com.prompthub.product.presentation.dto.response.seller.SellerProductDetailResponse;
import com.prompthub.product.presentation.dto.response.seller.SellerProductListItemResponse;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class ProductSellerService implements ProductSellerUseCase {

	private static final ProductType DEFAULT_PRODUCT_TYPE = ProductType.PROMPT;

	private final ProductRepository productRepository;
	private final ProductEventProducer productEventProducer;
	private final ObjectStorageGateway objectStorage;
	private final TempFilePromoter tempFilePromoter;

	@Override
	public ProductCreateResponse createProduct(UUID sellerId, ProductCreateRequest request) {
		ProductType productType = parseProductType(request.productType());
		AmountType amountType = request.amount() == 0 ? AmountType.FREE : AmountType.PAID;
		ProductContent.validateTypeFields(
			productType, request.content(), request.fileObjectKey(), request.externalUrl());

		UUID productId = UUID.randomUUID();
		// 임시 업로드를 상품이 계속 참조할 영구 key로 복사한다.
		TempFilePromoter.PromotedFiles storedFiles = tempFilePromoter.promote(
			request.thumbnailObjectKey(), request.imageObjectKeys(), request.fileObjectKey(), productId, sellerId);

		ProductContent content = new ProductContent(
			productType, request.title(), request.desc(), request.model(),
			amountType, request.amount(), storedFiles.thumbnailKey(), storedFiles.imageKeys(),
			request.content(), storedFiles.fileKey(), request.externalUrl(), request.tags());
		Product product = Product.create(productId, sellerId, content);

		Product saved = productRepository.save(product);
		productEventProducer.publishProductChanged(saved.familyRootId());

		return new ProductCreateResponse(
			saved.getId(),
			saved.getSellerId(),
			saved.getName(),
			saved.getProductType().name(),
			saved.getModel(),
			saved.getDescription(),
			saved.getAmount(),
			saved.getStatus().name(),
			saved.getCreatedAt()
		);
	}

	@Override
	public void submitForReview(UUID sellerId, UUID productId) {
		Product product = getOwnedProduct(sellerId, productId);
		product.submitForReview();
		productRepository.save(product);

		publishReviewRequestedEvent(product);
	}

	/** MAJOR 버전 전환으로 PENDING_REVIEW가 되는 모든 경로(submitForReview, MAJOR 수정)가 공유한다. */
	private void publishReviewRequestedEvent(Product product) {
		UUID duplicateOfProductId = findOriginalProductIdByContentHash(product);
		String presignedThumbnailUrl = createDownloadUrl(product.getThumbnailUrl());
		List<String> presignedImageUrls = createDownloadUrls(product.getImageUrls());
		productEventProducer.publishReviewRequested(
			product, duplicateOfProductId, presignedThumbnailUrl, presignedImageUrls);
	}

	@Override
	public ProductUpdateResponse updateProduct(UUID sellerId, UUID productId, ProductUpdateRequest request) {
		Product anchor = getOwnedProduct(sellerId, productId);

		ProductType requestedType = parseProductType(request.productType());
		if (requestedType != anchor.getProductType()) {
			throw new ProductException(ProductErrorCode.INVALID_PRODUCT_TYPE);
		}

		// 승격 전 원본 object key로 후보를 만든다 — no-op 판정은 반드시 승격보다 먼저 끝나야 한다.
		// 먼저 승격하면 안 바뀐 파일도 새 key가 생겨 변경으로 오판된다. 유형별 필드 검증은
		// ProductContent 생성자가 한다.
		AmountType amountType = request.amount() == 0 ? AmountType.FREE : AmountType.PAID;
		ProductContent candidate = new ProductContent(
			requestedType, request.title(), request.desc(), request.model(),
			amountType, request.amount(), request.thumbnailObjectKey(), request.imageObjectKeys(),
			request.content(), request.fileObjectKey(), request.externalUrl(), request.tags());

		if (anchor.getStatus() == ProductStatus.DRAFT) {
			ProductContent stored = promoteToPath(request, anchor.getId(), sellerId, candidate);
			anchor.updateDraftContent(stored);
			productRepository.save(anchor);
			return toResponse(anchor);
		}

		// 판매 후 반려된 row는 같은 version만 보정한다 — 새 row도, 기존 ON_SALE 교대도, 검수
		// 요청 이벤트도 만들지 않는다. 재검수는 판매자가 submitForReview()를 별도로 호출해야 한다.
		if (anchor.getStatus() == ProductStatus.REJECTED) {
			ProductContent stored = promoteToPath(request, anchor.getId(), sellerId, candidate);
			anchor.updateRejectedContent(stored);
			productRepository.save(anchor);
			return toResponse(anchor);
		}

		if (anchor.getStatus() != ProductStatus.ON_SALE) {
			throw new ProductException(ProductErrorCode.PRODUCT_INVALID_STATUS);
		}

		UUID familyRootId = anchor.familyRootId();
		ProductFamily family = ProductFamily.of(familyRootId, productRepository.findAllByFamilyRootIds(List.of(familyRootId)));
		Product onSale = family.currentOnSale()
			.orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_INVALID_STATUS));

		Optional<ProductVersionType> versionType = onSale.determineVersionType(candidate);
		if (versionType.isEmpty()) {
			return toResponse(onSale);
		}
		if (request.changeReason() == null || request.changeReason().isBlank()) {
			throw new ProductException(ProductErrorCode.INVALID_INPUT_VALUE);
		}
		if (versionType.get() == ProductVersionType.MAJOR && family.pendingReview().isPresent()) {
			throw new ProductException(ProductErrorCode.PRODUCT_INVALID_STATUS);
		}

		UUID nextProductId = UUID.randomUUID();
		ProductContent stored = promoteToPath(request, nextProductId, sellerId, candidate);
		Product next = onSale.createNextVersion(nextProductId, versionType.get(), stored, request.changeReason());

		if (versionType.get() == ProductVersionType.PATCH) {
			onSale.supersede();
			productRepository.save(onSale);
			productRepository.save(next);
			productEventProducer.publishProductChanged(familyRootId);
		} else {
			productRepository.save(next);
			publishReviewRequestedEvent(next);
		}

		if (onSale.getAmount() != request.amount()) {
			productEventProducer.publishPriceChanged(productId, onSale.getAmount(), request.amount());
		}

		return toResponse(next);
	}

	/** 새 temp key만 대상 경로로 복사한다. 기존 영구 key는 promote()가 그대로 통과시킨다. */
	private ProductContent promoteToPath(
		ProductUpdateRequest request, UUID targetProductId, UUID sellerId, ProductContent candidate
	) {
		TempFilePromoter.PromotedFiles storedFiles = tempFilePromoter.promote(
			request.thumbnailObjectKey(), request.imageObjectKeys(), request.fileObjectKey(), targetProductId, sellerId);
		return new ProductContent(
			candidate.productType(), candidate.name(), candidate.description(), candidate.model(),
			candidate.amountType(), candidate.amount(), storedFiles.thumbnailKey(), storedFiles.imageKeys(),
			candidate.content(), storedFiles.fileKey(), candidate.externalUrl(), candidate.tags());
	}

	private ProductUpdateResponse toResponse(Product product) {
		return new ProductUpdateResponse(
			product.getId(), product.getMajorVersion() + "." + product.getPatchVersion(), product.getStatus().name());
	}

	@Override
	public void deleteProduct(UUID sellerId, UUID productId) {
		Product product = productRepository.findById(productId)
			.orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));

		if (!product.isOwnedBy(sellerId)) {
			throw new ProductException(ProductErrorCode.PRODUCT_FORBIDDEN);
		}

		boolean isDraft = product.getStatus() == ProductStatus.DRAFT;
		if (isDraft) {
			product.softDelete();
		} else {
			product.stop();
		}
		productRepository.save(product);

		if (isDraft) {
			productEventProducer.publishDeleted(productId);
		} else {
			productEventProducer.publishStopped(productId);
		}
	}

	@Override
	@Transactional(readOnly = true)
	public List<SellerProductListItemResponse> getMyProducts(UUID sellerId) {
		List<Product> all = productRepository.findBySellerId(sellerId);
		Map<UUID, List<Product>> byFamily = all.stream()
			.collect(Collectors.groupingBy(Product::familyRootId));
		Map<UUID, Double> averageRatings = productRepository.getAverageRatings(List.copyOf(byFamily.keySet()));
		return byFamily.entrySet().stream()
			.map(entry -> {
				ProductFamily family = ProductFamily.of(entry.getKey(), entry.getValue());
				Product representative = family.currentForSeller()
					.orElseThrow(() -> new IllegalStateException("family에 대표 row가 없습니다. familyRootId=" + entry.getKey()));
				int familySalesCount = entry.getValue().stream().mapToInt(Product::getSalesCount).sum();
				double averageRating = averageRatings.getOrDefault(entry.getKey(), 0.0);
				return SellerProductListItemResponse.from(
					representative, familySalesCount, averageRating, createDownloadUrl(representative.getThumbnailUrl()));
			})
			.sorted(Comparator.comparing(SellerProductListItemResponse::updatedAt).reversed())
			.toList();
	}

	@Override
	@Transactional(readOnly = true)
	public SellerProductDetailResponse getMyProduct(UUID sellerId, UUID productId) {
		Product anchor = getOwnedProduct(sellerId, productId);
		UUID familyRootId = anchor.familyRootId();
		List<Product> members = productRepository.findAllByFamilyRootIds(List.of(familyRootId));
		ProductFamily family = ProductFamily.of(familyRootId, members);
		Product representative = family.currentForSeller().orElse(anchor);
		Product liveOnSale = family.currentOnSale().orElse(null);
		double averageRating = productRepository.getAverageRating(familyRootId);
		return SellerProductDetailResponse.from(
			representative, liveOnSale, family.sellerHistory(), averageRating,
			createDownloadUrl(representative.getThumbnailUrl()),
			createDownloadUrls(representative.getImageUrls()),
			createDownloadUrl(representative.getFileUrl()));
	}

	@Override
	@Transactional(readOnly = true)
	public ProductCountResponse getProductCount(UUID sellerId) {
		return new ProductCountResponse(
			sellerId,
			productRepository.countFamiliesBySellerId(sellerId),
			productRepository.sumSalesCountBySellerId(sellerId));
	}

	private String createDownloadUrl(String key) {
		return (key == null || key.isBlank()) ? null : objectStorage.createPresignedGetUrl(key);
	}

	private List<String> createDownloadUrls(List<String> keys) {
		if (keys == null || keys.isEmpty()) {
			return List.of();
		}
		return keys.stream().map(objectStorage::createPresignedGetUrl).toList();
	}

	private ProductType parseProductType(String productType) {
		if (productType == null || productType.isBlank()) {
			return DEFAULT_PRODUCT_TYPE;
		}
		try {
			return ProductType.valueOf(productType.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new ProductException(ProductErrorCode.INVALID_PRODUCT_TYPE);
		}
	}

	private Product getOwnedProduct(UUID sellerId, UUID productId) {
		Product product = productRepository.findById(productId)
			.orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));

		if (!product.isOwnedBy(sellerId)) {
			throw new ProductException(ProductErrorCode.PRODUCT_FORBIDDEN);
		}

		return product;
	}

	/** PROMPT가 아니면 content_hash가 없어 비교 대상이 아니다(ADR-0011). */
	private UUID findOriginalProductIdByContentHash(Product product) {
		if (product.getContentHash() == null) {
			return null;
		}
		return productRepository
			.findDuplicateOfProductId(product.getId(), product.getContentHash(), product.getSellerId())
			.orElse(null);
	}
}
