package com.prompthub.product.application.service.seller;

import com.prompthub.product.application.service.inspection.ProductInspectionRequestPublisher;
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
import java.util.function.Consumer;
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
	private final ProductInspectionRequestPublisher productInspectionRequestPublisher;
	private final ProductVersionChangePolicy versionChangePolicy;
	private final ProductVersionTransitionService versionTransition;
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

	/**
	 * MAJOR 버전 전환으로 PENDING_REVIEW가 되는 모든 경로(submitForReview, MAJOR 수정)가 공유한다.
	 * snapshot 조립은 최초 요청과 stale 재발행이 같은 계약을 쓰도록
	 * {@link ProductInspectionRequestPublisher}로 단일화돼 있다.
	 */
	private void publishReviewRequestedEvent(Product product) {
		productInspectionRequestPublisher.publish(product);
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
			return updateContentInPlace(anchor, request, sellerId, candidate, anchor::updateDraftContent);
		}

		// 판매 후 반려된 row는 같은 version만 보정한다 — 새 row도, 기존 ON_SALE 교대도, 검수
		// 요청 이벤트도 만들지 않는다. 재검수는 판매자가 submitForReview()를 별도로 호출해야 한다.
		if (anchor.getStatus() == ProductStatus.REJECTED) {
			return updateContentInPlace(anchor, request, sellerId, candidate, anchor::updateRejectedContent);
		}

		if (anchor.getStatus() != ProductStatus.ON_SALE) {
			throw new ProductException(ProductErrorCode.PRODUCT_INVALID_STATUS);
		}

		UUID familyRootId = anchor.familyRootId();
		ProductFamily family = ProductFamily.of(familyRootId, productRepository.findAllByFamilyRootIds(List.of(familyRootId)));
		Product onSale = family.currentOnSale()
			.orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_INVALID_STATUS));

		// 판정(no-op·changeReason·MAJOR 중복 대기 검증)은 policy가, 실제 반영(row 생성·저장·발행)은
		// transition이 맡는다 — 여기는 둘을 순서대로 호출하는 오케스트레이션만 한다.
		Optional<ProductVersionType> versionType =
			versionChangePolicy.decideVersionChange(onSale, candidate, family, request.changeReason());
		if (versionType.isEmpty()) {
			return toResponse(onSale);
		}

		UUID nextProductId = UUID.randomUUID();
		ProductContent stored = promoteToPath(request, nextProductId, sellerId, candidate);
		Product next = versionTransition.transitionToNextVersion(
			onSale, nextProductId, versionType.get(), stored, request.changeReason());

		return toResponse(next);
	}

	/** DRAFT·REJECTED 공통 — 같은 row·같은 version에서 콘텐츠만 보정한다. 상태별 불변식 검증은 applyContent가 맡는다. */
	private ProductUpdateResponse updateContentInPlace(
		Product anchor, ProductUpdateRequest request, UUID sellerId, ProductContent candidate, Consumer<ProductContent> applyContent
	) {
		ProductContent stored = promoteToPath(request, anchor.getId(), sellerId, candidate);
		applyContent.accept(stored);
		productRepository.save(anchor);
		return toResponse(anchor);
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

		if (product.getStatus() == ProductStatus.DRAFT) {
			product.softDelete();
		} else {
			product.stop();
		}
		productRepository.save(product);
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
				// family에 대표 row가 없는 건 클라이언트 요청 문제가 아니라 데이터 정합성 위반이다.
				// 원인 파악을 위해 familyRootId를 메시지에 남기고, HTTP 응답은 409로 통일한다.
				Product representative = family.currentForSeller()
					.orElseThrow(() -> new ProductException(
						ProductErrorCode.PRODUCT_INVALID_STATUS, "family에 대표 row가 없습니다. familyRootId=" + entry.getKey()));
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
}
