package com.prompthub.product.application.service;

import com.prompthub.product.application.client.SellerClient;
import com.prompthub.product.application.client.StorageClient;
import com.prompthub.product.application.usecase.ProductInternalUseCase;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.entity.ProductFamily;
import com.prompthub.product.domain.model.entity.Review;
import com.prompthub.product.domain.repository.ProductRepository;
import com.prompthub.product.domain.repository.ReviewRepository;
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.exception.enums.ProductErrorCode;
import com.prompthub.product.presentation.dto.response.ProductCartSnapshotResponse;
import com.prompthub.product.presentation.dto.response.ProductContentResponse;
import com.prompthub.product.presentation.dto.response.ProductCountResponse;
import com.prompthub.product.presentation.dto.response.ProductOrderSnapshotResponse;
import com.prompthub.product.presentation.dto.response.ProductsByIdsResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductInternalService implements ProductInternalUseCase {

	private final ProductRepository productRepository;
	private final ReviewRepository reviewRepository;
	private final SellerClient sellerClient;
	private final StorageClient storageClient;

	@Override
	public List<ProductsByIdsResponse> getProductsByIds(List<UUID> productIds) {
		Map<UUID, Product> resolved = resolveFamilyRepresentatives(productIds, ProductFamily::currentForWishlist);
		return productIds.stream()
			.filter(resolved::containsKey)
			.map(id -> {
				Product p = resolved.get(id);
				return new ProductsByIdsResponse(
					id,
					p.getSellerId(),
					p.getName(),
					p.getAmount(),
					p.getThumbnailUrl(),
					p.getProductType().name(),
					p.getModel() != null ? p.getModel() : "",
					(int) productRepository.sumSalesCountByFamilyRootId(p.familyRootId()),
					productRepository.getAverageRating(p.familyRootId()),
					p.getStatus().name()
				);
			})
			.toList();
	}

	@Override
	public List<ProductOrderSnapshotResponse> getOrderSnapshots(List<UUID> productIds) {
		Map<UUID, Product> resolved = resolveFamilyRepresentatives(productIds, ProductFamily::currentOnSale);
		return productIds.stream()
			.filter(resolved::containsKey)
			.map(id -> ProductOrderSnapshotResponse.from(id, resolved.get(id)))
			.toList();
	}

	@Override
	public ProductCartSnapshotResponse getCartSnapshot(UUID productId) {
		Map<UUID, Product> resolved = resolveFamilyRepresentatives(List.of(productId), ProductFamily::currentOnSale);
		Product product = resolved.get(productId);
		if (product == null) {
			throw new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND);
		}
		String sellerNickname = sellerClient.getSellerInfo(product.getSellerId()).sellerName();
		return ProductCartSnapshotResponse.from(productId, product, sellerNickname);
	}

	@Override
	public List<ProductCartSnapshotResponse> getCartSnapshots(List<UUID> productIds) {
		Map<UUID, Product> resolved = resolveFamilyRepresentatives(productIds, ProductFamily::currentOnSale);
		Map<UUID, String> sellerNicknames = resolved.values().stream()
			.map(Product::getSellerId)
			.distinct()
			.collect(Collectors.toMap(id -> id, id -> sellerClient.getSellerInfo(id).sellerName()));
		return productIds.stream()
			.filter(resolved::containsKey)
			.map(id -> {
				Product product = resolved.get(id);
				return ProductCartSnapshotResponse.from(id, product, sellerNicknames.get(product.getSellerId()));
			})
			.toList();
	}

	@Override
	public ProductContentResponse getProductContent(UUID productId) {
		Map<UUID, Product> resolved = resolveFamilyRepresentatives(List.of(productId), ProductFamily::currentOnSale);
		Product product = resolved.get(productId);
		if (product == null) {
			throw new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND);
		}
		return new ProductContentResponse(productId, resolveDeliverable(product));
	}

	private String resolveDeliverable(Product product) {
		return switch (product.getProductType()) {
			case PROMPT -> product.getContent();
			case PPT, EXCEL -> presignIfPresent(product.getFileUrl());
			case NOTION -> product.getExternalUrl();
		};
	}

	private String presignIfPresent(String key) {
		if (key == null || key.isBlank()) {
			return null;
		}
		return storageClient.generatePresignedDownloadUrl(key);
	}

	@Override
	@Transactional
	public void upsertReview(UUID buyerId, UUID productId, Integer rating) {
		Product anchor = productRepository.findById(productId)
			.orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));
		Product root = productRepository.findById(anchor.familyRootId())
			.orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));
		reviewRepository.findByUserIdAndProductId(buyerId, root.getId())
			.ifPresentOrElse(
				review -> review.updateRating((short) rating.intValue()),
				() -> reviewRepository.save(Review.create(buyerId, root, (short) rating.intValue()))
			);
	}

	@Override
	public ProductCountResponse getProductCount(UUID sellerId) {
		return new ProductCountResponse(
			sellerId,
			productRepository.countFamiliesBySellerId(sellerId),
			productRepository.sumSalesCountBySellerId(sellerId));
	}

	private Map<UUID, Product> resolveFamilyRepresentatives(
		List<UUID> requestedIds,
		Function<ProductFamily, Optional<Product>> selector
	) {
		List<Product> anchors = productRepository.findAllByIdIn(requestedIds);
		Map<UUID, UUID> familyRootByRequestedId = anchors.stream()
			.collect(Collectors.toMap(Product::getId, Product::familyRootId));
		List<UUID> familyRootIds = familyRootByRequestedId.values().stream().distinct().toList();
		List<Product> allMembers = familyRootIds.isEmpty()
			? List.of()
			: productRepository.findAllByFamilyRootIds(familyRootIds);
		Map<UUID, List<Product>> membersByFamily = allMembers.stream()
			.collect(Collectors.groupingBy(Product::familyRootId));

		Map<UUID, Product> result = new LinkedHashMap<>();
		for (UUID requestedId : requestedIds) {
			UUID familyRootId = familyRootByRequestedId.get(requestedId);
			if (familyRootId == null) {
				continue;
			}
			ProductFamily family = ProductFamily.of(familyRootId, membersByFamily.getOrDefault(familyRootId, List.of()));
			selector.apply(family).ifPresent(product -> result.put(requestedId, product));
		}
		return result;
	}
}
