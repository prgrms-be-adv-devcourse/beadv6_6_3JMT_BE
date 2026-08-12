package com.prompthub.product.application.service.inspection;

import com.prompthub.product.application.gateway.external.ObjectStorageGateway;
import com.prompthub.product.application.usecase.inspection.ProductEventPublisher;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.repository.ProductRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 검수 요청 이벤트에 실을 snapshot(중복 후보·presigned 미리보기 URL)을 조립해 발행한다.
 * 최초 요청과 stale 재발행({@link ProductInspectionRequestRetryService})이
 * 같은 계약을 쓰도록 이 한 경로로 단일화한다. Kafka 구현은 직접 알지 않고
 * {@link ProductEventPublisher} 포트만 의존한다.
 */
@Service
@RequiredArgsConstructor
public class ProductInspectionRequestPublisher {

	private final ProductEventPublisher productEventPublisher;
	private final ProductRepository productRepository;
	private final ObjectStorageGateway objectStorage;

	public void publish(Product product) {
		UUID duplicateOfProductId = findOriginalProductIdByContentHash(product);
		String presignedThumbnailUrl = objectStorage.presignIfPresent(product.getThumbnailUrl());
		List<String> presignedImageUrls = objectStorage.presignAllIfPresent(product.getImageUrls());
		productEventPublisher.publishReviewRequested(
			product, duplicateOfProductId, presignedThumbnailUrl, presignedImageUrls);
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
