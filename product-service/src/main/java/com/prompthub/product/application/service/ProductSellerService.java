package com.prompthub.product.application.service;

import com.prompthub.product.application.client.SellerClient;
import com.prompthub.product.application.client.SellerInfo;
import com.prompthub.product.application.client.StorageClient;
import com.prompthub.product.application.usecase.ProductSellerUseCase;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.entity.ProductFamily;
import com.prompthub.product.domain.model.enums.AmountType;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.model.enums.ProductType;
import com.prompthub.product.domain.repository.ProductRepository;
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.exception.enums.ProductErrorCode;
import com.prompthub.product.infra.messaging.producer.ProductEventProducer;
import com.prompthub.product.presentation.dto.request.ProductCreateRequest;
import com.prompthub.product.presentation.dto.request.ProductUpdateRequest;
import com.prompthub.product.presentation.dto.response.ProductCreateResponse;
import com.prompthub.product.presentation.dto.response.SellerProductDetailResponse;
import com.prompthub.product.presentation.dto.response.SellerProductListItemResponse;
import java.util.Arrays;
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

	private static final String TEMP_PREFIX = "products/temp/";
	private static final ProductType DEFAULT_PRODUCT_TYPE = ProductType.PROMPT;

	private final ProductRepository productRepository;
	private final SellerClient sellerClient;
	private final ProductEventProducer productEventProducer;
	private final StorageClient storageClient;

	@Override
	public ProductCreateResponse createProduct(UUID sellerId, ProductCreateRequest request) {
		SellerInfo seller = sellerClient.getSellerInfo(sellerId);
		if (!"ACTIVE".equals(seller.status())) {
			throw new ProductException(ProductErrorCode.SELLER_NOT_ACTIVE);
		}

		ProductType productType = parseProductType(request.productType());

		UUID productId = UUID.randomUUID();
		String thumbnailKey = moveToProductPath(extractKey(request.thumbnailUrl()), productId);
		List<String> imageKeys = moveToProductPaths(extractKeys(request.imageUrls()), productId);

		AmountType amountType = request.amount() == 0 ? AmountType.FREE : AmountType.PAID;
		Product product = Product.create(
			productId,
			sellerId,
			productType,
			request.title(),
			request.desc(),
			request.model(),
			amountType,
			request.amount(),
			thumbnailKey,
			imageKeys,
			request.content(),
			request.tags()
		);

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
	public void updateProduct(UUID sellerId, UUID productId, ProductUpdateRequest request) {
		Product anchor = getProductForSeller(sellerId, productId);

		ProductType productType = parseProductType(request.productType());
		AmountType amountType = request.amount() == 0 ? AmountType.FREE : AmountType.PAID;
		boolean isMajor = "MAJOR".equalsIgnoreCase(request.versionType());
		String newThumbnailKey = moveToProductPath(extractKey(request.thumbnailUrl()), productId);
		List<String> newImageKeys = moveToProductPaths(extractKeys(request.imageUrls()), productId);

		UUID familyRootId = anchor.familyRootId();
		ProductFamily family = ProductFamily.of(familyRootId, productRepository.findAllByFamilyRootIds(List.of(familyRootId)));

		int previousPrice;
		if (!family.hasEverBeenOnSale()) {
			previousPrice = anchor.getAmount();
			anchor.update(
				productType, request.title(), request.desc(), request.model(), amountType, request.amount(),
				newThumbnailKey, newImageKeys, request.content(), request.tags(), request.changeReason(), isMajor
			);
			productRepository.save(anchor);
		} else {
			Product onSale = family.currentOnSale()
				.orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_INVALID_STATUS));
			previousPrice = onSale.getAmount();

			if (isMajor) {
				if (family.pendingReview().isPresent()) {
					throw new ProductException(ProductErrorCode.PRODUCT_INVALID_STATUS);
				}
				Product next = onSale.nextVersion(
					true, productType, request.title(), request.desc(), request.model(), amountType, request.amount(),
					newThumbnailKey, newImageKeys, request.content(), request.tags(), request.changeReason()
				);
				productRepository.save(next);
			} else {
				Product next = onSale.nextVersion(
					false, productType, request.title(), request.desc(), request.model(), amountType, request.amount(),
					newThumbnailKey, newImageKeys, request.content(), request.tags(), request.changeReason()
				);
				onSale.supersede();
				productRepository.save(onSale);
				productRepository.save(next);
			}
		}

		if (previousPrice != request.amount()) {
			productEventProducer.publishPriceChanged(productId, previousPrice, request.amount());
		}
	}

	@Override
	public void deleteProduct(UUID sellerId, String role, UUID productId) {
		Product product = productRepository.findById(productId)
			.orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));

		if (Arrays.stream(role.split(",")).noneMatch("ADMIN"::equals) && !product.isOwnedBy(sellerId)) {
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
		return byFamily.entrySet().stream()
			.map(entry -> {
				ProductFamily family = ProductFamily.of(entry.getKey(), entry.getValue());
				Product representative = family.currentForSeller()
					.orElseThrow(() -> new IllegalStateException("family에 대표 row가 없습니다. familyRootId=" + entry.getKey()));
				int familySalesCount = entry.getValue().stream().mapToInt(Product::getSalesCount).sum();
				return SellerProductListItemResponse.from(representative, familySalesCount);
			})
			.sorted(Comparator.comparing(SellerProductListItemResponse::updatedAt).reversed())
			.toList();
	}

	@Override
	public void submitForReview(UUID sellerId, UUID productId) {
		Product product = getProductForSeller(sellerId, productId);
		product.submitForReview();
		productRepository.save(product);
	}

	@Override
	@Transactional(readOnly = true)
	public SellerProductDetailResponse getMyProduct(UUID sellerId, UUID productId) {
		Product anchor = getProductForSeller(sellerId, productId);
		UUID familyRootId = anchor.familyRootId();
		List<Product> members = productRepository.findAllByFamilyRootIds(List.of(familyRootId));
		ProductFamily family = ProductFamily.of(familyRootId, members);
		Product representative = family.currentForSeller().orElse(anchor);
		Product liveOnSale = family.currentOnSale().orElse(null);
		return SellerProductDetailResponse.from(representative, liveOnSale, family.sellerHistory(), storageClient);
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

	private String moveToProductPath(String key, UUID productId) {
		if (key == null || key.isBlank() || !key.startsWith(TEMP_PREFIX)) return key;
		String destKey = "products/" + productId + "/" + key.substring(TEMP_PREFIX.length());
		storageClient.copyObject(key, destKey);
		storageClient.deleteObject(key);
		return destKey;
	}

	private List<String> moveToProductPaths(List<String> keys, UUID productId) {
		if (keys == null) return null;
		return keys.stream().map(k -> moveToProductPath(k, productId)).toList();
	}

	private String extractKey(String presignedUrl) {
		if (presignedUrl == null || presignedUrl.isBlank()) return null;
		String path = presignedUrl.split("\\?")[0];
		int idx = path.indexOf(".amazonaws.com/");
		return idx >= 0 ? path.substring(idx + ".amazonaws.com/".length()) : presignedUrl;
	}

	private List<String> extractKeys(List<String> urls) {
		if (urls == null) return null;
		return urls.stream().map(this::extractKey).toList();
	}

	private Product getProductForSeller(UUID sellerId, UUID productId) {
		Product product = productRepository.findById(productId)
			.orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));

		if (!product.isOwnedBy(sellerId)) {
			throw new ProductException(ProductErrorCode.PRODUCT_FORBIDDEN);
		}

		return product;
	}
}
