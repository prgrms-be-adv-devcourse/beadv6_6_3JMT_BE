package com.prompthub.product.application.service;

import com.prompthub.product.application.client.StorageClient;
import com.prompthub.product.application.usecase.PurchasedProductQueryUseCase;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.entity.ProductFamily;
import com.prompthub.product.domain.repository.ProductRepository;
import com.prompthub.product.domain.repository.ReviewRepository;
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.exception.enums.ProductErrorCode;
import com.prompthub.product.presentation.dto.response.PurchasedProductDetailResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PurchasedProductQueryService implements PurchasedProductQueryUseCase {

	private final ProductFamilyResolver productFamilyResolver;
	private final ProductRepository productRepository;
	private final ReviewRepository reviewRepository;
	private final StorageClient storageClient;

	@Override
	public PurchasedProductDetailResponse getPurchasedProduct(UUID userId, UUID productId) {
		verifyPurchase(userId, productId);
		Map<UUID, Product> resolved =
			productFamilyResolver.resolveFamilyRepresentatives(List.of(productId), ProductFamily::currentOnSale);
		Product product = resolved.get(productId);
		if (product == null) {
			throw new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND);
		}
		UUID familyRootId = product.familyRootId();
		double averageRating = productRepository.getAverageRating(familyRootId);
		Integer myRating = reviewRepository.findByUserIdAndProductId(userId, familyRootId)
			.map(review -> (int) review.getRating())
			.orElse(null);
		return buildResponse(productId, product, averageRating, myRating);
	}

	// 유형별 콘텐츠: PROMPT=본문, PPT·EXCEL=presigned 다운로드 URL(DB 값은 S3 키), NOTION=외부 링크
	private PurchasedProductDetailResponse buildResponse(
		UUID requestedId, Product product, double averageRating, Integer myRating
	) {
		String content = null;
		String fileUrl = null;
		String externalUrl = null;
		switch (product.getProductType()) {
			case PROMPT -> content = product.getContent();
			case PPT, EXCEL -> fileUrl = presignIfPresent(product.getFileUrl());
			case NOTION -> externalUrl = product.getExternalUrl();
		}
		return PurchasedProductDetailResponse.of(
			requestedId, product, content, fileUrl, externalUrl, averageRating, myRating);
	}

	private String presignIfPresent(String key) {
		if (key == null || key.isBlank()) {
			return null;
		}
		return storageClient.generatePresignedDownloadUrl(key);
	}

	// 구매 여부 검증 지점 — 현재는 검증하지 않는다(#550 설계 결정). 후속 이슈에서 order-service gRPC 검증으로 대체한다.
	private void verifyPurchase(UUID userId, UUID productId) {
	}
}
