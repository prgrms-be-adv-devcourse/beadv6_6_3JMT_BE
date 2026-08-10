package com.prompthub.product.application.service;

import com.prompthub.product.application.gateway.external.ObjectStorageGateway;
import com.prompthub.product.application.service.fileupload.TempFilePromoter;
import com.prompthub.product.application.usecase.ProductSellerUseCase;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.entity.ProductFamily;
import com.prompthub.product.domain.model.enums.AmountType;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.model.enums.ProductType;
import com.prompthub.product.domain.model.vo.ProductContent;
import com.prompthub.product.domain.repository.ProductRepository;
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.exception.enums.ProductErrorCode;
import com.prompthub.product.infra.messaging.producer.ProductEventProducer;
import com.prompthub.product.presentation.dto.request.ProductCreateRequest;
import com.prompthub.product.presentation.dto.request.ProductUpdateRequest;
import com.prompthub.product.presentation.dto.response.ProductCountResponse;
import com.prompthub.product.presentation.dto.response.ProductCreateResponse;
import com.prompthub.product.presentation.dto.response.SellerProductDetailResponse;
import com.prompthub.product.presentation.dto.response.SellerProductListItemResponse;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
	public void updateProduct(UUID sellerId, UUID productId, ProductUpdateRequest request) {
		Product anchor = getOwnedProduct(sellerId, productId);

		ProductType productType = parseProductType(request.productType());
		AmountType amountType = request.amount() == 0 ? AmountType.FREE : AmountType.PAID;
		boolean isMajor = "MAJOR".equalsIgnoreCase(request.versionType());
		ProductContent.validateTypeFields(
			productType, request.content(), request.fileObjectKey(), request.externalUrl());

		// 새 temp key는 영구 key로 복사하고, 기존 영구 key는 그대로 유지한다.
		TempFilePromoter.PromotedFiles storedFiles = tempFilePromoter.promote(
			request.thumbnailObjectKey(), request.imageObjectKeys(), request.fileObjectKey(), productId, sellerId);
		ProductContent content = new ProductContent(
			productType, request.title(), request.desc(), request.model(),
			amountType, request.amount(), storedFiles.thumbnailKey(), storedFiles.imageKeys(),
			request.content(), storedFiles.fileKey(), request.externalUrl(), request.tags());

		UUID familyRootId = anchor.familyRootId();
		ProductFamily family = ProductFamily.of(familyRootId, productRepository.findAllByFamilyRootIds(List.of(familyRootId)));

		int previousPrice;
		if (!family.hasEverBeenOnSale()) {
			previousPrice = anchor.getAmount();
			anchor.update(content, request.changeReason(), isMajor);
			productRepository.save(anchor);
			if (isMajor) {
				publishReviewRequestedEvent(anchor);
			}
		} else {
			Product onSale = family.currentOnSale()
				.orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_INVALID_STATUS));
			previousPrice = onSale.getAmount();

			if (isMajor) {
				if (family.pendingReview().isPresent()) {
					throw new ProductException(ProductErrorCode.PRODUCT_INVALID_STATUS);
				}
				Product next = onSale.nextVersion(true, content, request.changeReason());
				productRepository.save(next);
				publishReviewRequestedEvent(next);
			} else {
				Product next = onSale.nextVersion(false, content, request.changeReason());
				onSale.supersede();
				productRepository.save(onSale);
				productRepository.save(next);
				productEventProducer.publishProductChanged(familyRootId);
			}
		}

		if (previousPrice != request.amount()) {
			productEventProducer.publishPriceChanged(productId, previousPrice, request.amount());
		}
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
