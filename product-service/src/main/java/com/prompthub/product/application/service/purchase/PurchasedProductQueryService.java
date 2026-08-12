package com.prompthub.product.application.service.purchase;

import com.prompthub.product.application.service.query.ProductFamilyResolver;
import com.prompthub.product.application.gateway.external.ObjectStorageGateway;
import com.prompthub.product.application.usecase.purchase.PurchasedProductQueryUseCase;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.entity.ProductFamily;
import com.prompthub.product.domain.model.vo.ProductDeliverable;
import com.prompthub.product.domain.repository.ProductRepository;
import com.prompthub.product.domain.repository.ReviewRepository;
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.exception.enums.ProductErrorCode;
import com.prompthub.product.presentation.dto.response.purchase.PurchasedProductDetailResponse;
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
	private final ObjectStorageGateway objectStorage;

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
		return createPurchasedProductResponse(productId, product, averageRating, myRating);
	}

	// 유형별 콘텐츠: PROMPT=본문, PPT·EXCEL=presigned 다운로드 URL(DB 값은 S3 키), NOTION=외부 링크
	private PurchasedProductDetailResponse createPurchasedProductResponse(
		UUID requestedId, Product product, double averageRating, Integer myRating
	) {
		ProductDeliverable deliverable = product.resolveDeliverable();
		String content = deliverable.type() == ProductDeliverable.Type.INLINE_CONTENT ? deliverable.value() : null;
		String fileUrl = deliverable.type() == ProductDeliverable.Type.FILE_OBJECT_KEY
			? objectStorage.createPresignedGetUrl(deliverable.value()) : null;
		String externalUrl = deliverable.type() == ProductDeliverable.Type.EXTERNAL_URL ? deliverable.value() : null;
		return PurchasedProductDetailResponse.of(
			requestedId, product, content, fileUrl, externalUrl,
			presignIfPresent(product.getThumbnailUrl()), averageRating, myRating);
	}

	private String presignIfPresent(String key) {
		if (key == null || key.isBlank()) {
			return null;
		}
		return objectStorage.createPresignedGetUrl(key);
	}

	// 구매 여부 검증 지점 — 현재는 검증하지 않는다(#550 설계 결정). 후속 이슈에서 order-service gRPC 검증으로 대체한다.
	private void verifyPurchase(UUID userId, UUID productId) {
	}
}
