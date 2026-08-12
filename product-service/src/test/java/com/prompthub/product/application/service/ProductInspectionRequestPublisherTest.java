package com.prompthub.product.application.service;
import com.prompthub.product.application.service.inspection.ProductInspectionRequestPublisher;

import static com.prompthub.product.support.ProductContentFixtures.notionContent;
import static com.prompthub.product.support.ProductContentFixtures.promptContent;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.prompthub.product.application.gateway.external.ObjectStorageGateway;
import com.prompthub.product.application.usecase.inspection.ProductEventPublisher;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.repository.ProductRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 최초 요청과 stale 재발행이 같은 계약을 쓰도록 단일화된 snapshot 조립 경로를 검증한다
 * (2026-08-05 로드맵 PR4).
 */
@ExtendWith(MockitoExtension.class)
class ProductInspectionRequestPublisherTest {

	private static final UUID PRODUCT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	@Mock
	private ProductEventPublisher productEventPublisher;

	@Mock
	private ProductRepository productRepository;

	@Mock
	private ObjectStorageGateway objectStorage;

	@InjectMocks
	private ProductInspectionRequestPublisher productInspectionRequestPublisher;

	@Test
	@DisplayName("썸네일/이미지를 presign해 발행하고 duplicateOfProductId를 함께 담는다")
	void publish_presignsImagesAndIncludesDuplicate() {
		Product product = Product.create(PRODUCT_ID, UUID.randomUUID(), promptContent());
		ReflectionTestUtils.setField(product, "thumbnailUrl", "products/1/thumbnail/a.png");
		ReflectionTestUtils.setField(product, "imageUrls", List.of("products/1/image/b.png"));
		UUID originalProductId = UUID.randomUUID();
		given(objectStorage.presignIfPresent("products/1/thumbnail/a.png")).willReturn("https://s3/thumb");
		given(objectStorage.presignAllIfPresent(List.of("products/1/image/b.png"))).willReturn(List.of("https://s3/image"));
		given(productRepository.findDuplicateOfProductId(PRODUCT_ID, product.getContentHash(), product.getSellerId()))
			.willReturn(Optional.of(originalProductId));

		productInspectionRequestPublisher.publish(product);

		then(productEventPublisher).should().publishReviewRequested(
			product, originalProductId, "https://s3/thumb", List.of("https://s3/image"));
	}

	@Test
	@DisplayName("썸네일이 없으면 presign 없이 null로 발행한다")
	void publish_withoutThumbnail_publishesNullThumbnail() {
		Product product = Product.create(PRODUCT_ID, UUID.randomUUID(), promptContent());
		given(productRepository.findDuplicateOfProductId(PRODUCT_ID, product.getContentHash(), product.getSellerId()))
			.willReturn(Optional.empty());

		productInspectionRequestPublisher.publish(product);

		then(productEventPublisher).should().publishReviewRequested(product, null, null, List.of());
		then(objectStorage).should().presignIfPresent(null);
		then(objectStorage).should().presignAllIfPresent(List.of());
	}

	@Test
	@DisplayName("본문이 없는 유형(content_hash null)은 중복 조회 자체를 호출하지 않는다")
	void publish_withoutContentHash_neverQueriesForDuplicate() {
		Product product = Product.create(PRODUCT_ID, UUID.randomUUID(), notionContent("노션 상품", 1000));

		productInspectionRequestPublisher.publish(product);

		then(productRepository).should(never()).findDuplicateOfProductId(any(), any(), any());
		then(productEventPublisher).should().publishReviewRequested(product, null, null, List.of());
	}
}
