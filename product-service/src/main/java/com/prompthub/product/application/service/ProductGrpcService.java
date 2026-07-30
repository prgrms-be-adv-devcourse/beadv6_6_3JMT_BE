package com.prompthub.product.application.service;

import com.prompthub.product.application.client.StorageClient;
import com.prompthub.product.application.usecase.ProductGrpcUseCase;
import com.prompthub.product.application.usecase.ProductQueryUseCase;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.entity.ProductFamily;
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.exception.enums.ProductErrorCode;
import com.prompthub.product.presentation.dto.response.ProductCartSnapshotResponse;
import com.prompthub.product.presentation.dto.response.ProductContentResponse;
import com.prompthub.product.presentation.dto.response.ProductListItemResponse;
import com.prompthub.product.presentation.dto.response.ProductOrderSnapshotResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductGrpcService implements ProductGrpcUseCase {

	private final ProductFamilyResolver productFamilyResolver;
	private final StorageClient storageClient;
	private final ProductQueryUseCase productQueryUseCase;

	@Override
	public List<ProductOrderSnapshotResponse> getOrderSnapshots(List<UUID> productIds) {
		Map<UUID, Product> resolved = productFamilyResolver.resolveFamilyRepresentatives(productIds, ProductFamily::currentOnSale);
		return productIds.stream()
			.filter(resolved::containsKey)
			.map(id -> ProductOrderSnapshotResponse.from(id, resolved.get(id)))
			.toList();
	}

	@Override
	public List<ProductCartSnapshotResponse> getCartSnapshots(List<UUID> productIds) {
		Map<UUID, Product> resolved = productFamilyResolver.resolveFamilyRepresentatives(productIds, ProductFamily::currentOnSale);
		return productIds.stream()
			.filter(resolved::containsKey)
			.map(id -> ProductCartSnapshotResponse.from(id, resolved.get(id), null))
			.toList();
	}

	@Override
	public ProductContentResponse getProductContent(UUID productId) {
		Map<UUID, Product> resolved = productFamilyResolver.resolveFamilyRepresentatives(List.of(productId), ProductFamily::currentOnSale);
		Product product = resolved.get(productId);
		if (product == null) {
			throw new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND);
		}
		return new ProductContentResponse(productId, resolveDeliverable(product));
	}

	/**
	 * 기준마다 따로 조회한다. 한 번의 쿼리로 묶지 않는 이유는 유사도가 <b>기준 상품과의</b>
	 * 거리라 기준이 바뀌면 순위가 통째로 달라지기 때문이다 — 합쳐서 뽑을 수 있는 값이 아니다.
	 * 기준 개수는 호출자가 활동 내역에서 추린 소수이고, 판단에 필요한 만큼만 넘어온다.
	 */
	@Override
	public Map<UUID, List<ProductListItemResponse>> getSimilarProducts(List<UUID> seedProductIds, int limitPerSeed) {
		Map<UUID, List<ProductListItemResponse>> rankings = new LinkedHashMap<>();
		for (UUID seedProductId : seedProductIds) {
			rankings.put(seedProductId, similarProductsOf(seedProductId, limitPerSeed));
		}
		return rankings;
	}

	/**
	 * 기준 하나가 실패해도 나머지 기준의 순위는 살린다. 판매가 끝났거나 임베딩이 아직 없는
	 * 상품이 활동 내역에 섞여 있는 건 정상 상태다 — 그걸로 추천 전체를 실패시키지 않는다.
	 */
	private List<ProductListItemResponse> similarProductsOf(UUID seedProductId, int limitPerSeed) {
		try {
			return productQueryUseCase.getRecommendedProducts(seedProductId, limitPerSeed);
		} catch (ProductException e) {
			log.debug("기준 상품으로 추천을 뽑지 못해 건너뜁니다. seedProductId={}", seedProductId, e);
			return List.of();
		}
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
}
