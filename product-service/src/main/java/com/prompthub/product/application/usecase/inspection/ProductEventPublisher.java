package com.prompthub.product.application.usecase.inspection;

import com.prompthub.product.domain.model.entity.Product;
import java.util.List;
import java.util.UUID;

/**
 * product-events 발행 아웃바운드 포트(내부 기술 인프라). 구현은
 * {@code infra/messaging/producer/ProductEventProducer}가 맡는다.
 */
public interface ProductEventPublisher {

	void publishReviewRequested(
		Product product, UUID duplicateOfProductId, String presignedThumbnailUrl, List<String> presignedImageUrls);
}
