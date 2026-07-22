package com.prompthub.product.application.service;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.model.enums.ProductType;
import com.prompthub.product.domain.repository.ProductRepository;
import com.prompthub.product.domain.repository.ReviewRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class ProductReviewServiceTest {

	private static final UUID PRODUCT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID SELLER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

	@Mock
	private ProductRepository productRepository;

	@Mock
	private ReviewRepository reviewRepository;

	@InjectMocks
	private ProductReviewService productReviewService;

	@Nested
	@DisplayName("리뷰 등록/수정")
	class UpsertReview {

		@Test
		@DisplayName("자식 row의 id로 요청해도 family root에 리뷰가 귀속된다")
		void upsertReview_attachesReviewToFamilyRoot() {
			UUID rootId = UUID.fromString("44444444-4444-4444-4444-444444444444");
			Product root = product(rootId, SELLER_ID, ProductStatus.SUPERSEDED);
			Product child = product(PRODUCT_ID, SELLER_ID, ProductStatus.ON_SALE);
			ReflectionTestUtils.setField(child, "parentId", rootId);

			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(child));
			given(productRepository.findById(rootId)).willReturn(Optional.of(root));
			given(reviewRepository.findByUserIdAndProductId(SELLER_ID, rootId)).willReturn(Optional.empty());

			productReviewService.upsertReview(SELLER_ID, PRODUCT_ID, 5);

			ArgumentCaptor<com.prompthub.product.domain.model.entity.Review> captor =
				ArgumentCaptor.forClass(com.prompthub.product.domain.model.entity.Review.class);
			then(reviewRepository).should().save(captor.capture());
			assertThat(captor.getValue().getProduct()).isEqualTo(root);
		}
	}

	private Product product(UUID id, UUID sellerId, ProductStatus status) {
		Product product = instantiate(Product.class);
		ReflectionTestUtils.setField(product, "id", id);
		ReflectionTestUtils.setField(product, "sellerId", sellerId);
		ReflectionTestUtils.setField(product, "name", "면접 답변 프롬프트");
		ReflectionTestUtils.setField(product, "productType", ProductType.PROMPT);
		ReflectionTestUtils.setField(product, "amount", 15000);
		ReflectionTestUtils.setField(product, "status", status);
		return product;
	}

	private <T> T instantiate(Class<T> type) {
		try {
			java.lang.reflect.Constructor<T> constructor = type.getDeclaredConstructor();
			constructor.setAccessible(true);
			return constructor.newInstance();
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException("테스트 fixture 생성에 실패했습니다.", exception);
		}
	}
}
