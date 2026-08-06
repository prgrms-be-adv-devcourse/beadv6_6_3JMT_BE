package com.prompthub.product.application.service;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.entity.Review;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.never;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.times;

@ExtendWith(MockitoExtension.class)
class ProductReviewServiceTest {

	private static final UUID PRODUCT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID SELLER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
	private static final UUID BUYER_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
	private static final UUID BUYER_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

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

		@Test
		@DisplayName("같은 사용자가 다시 요청하면 새 리뷰를 만들지 않고 기존 평점만 수정한다")
		void upsertReview_updatesExistingReviewInsteadOfCreatingAnother() {
			Product root = product(PRODUCT_ID, SELLER_ID, ProductStatus.ON_SALE);
			Review existing = Review.create(BUYER_A, root, (short) 2);

			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(root));
			given(reviewRepository.findByUserIdAndProductId(BUYER_A, PRODUCT_ID))
				.willReturn(Optional.of(existing));

			productReviewService.upsertReview(BUYER_A, PRODUCT_ID, 5);

			// 리뷰가 하나 더 생기면 평균이 (2+5)/2로 왜곡된다. 기존 row를 고쳐야 한다
			assertThat(existing.getRating()).isEqualTo((short) 5);
			then(reviewRepository).should(never()).save(any());
		}

		@Test
		@DisplayName("같은 상품이라도 사용자가 다르면 각자 리뷰를 갖는다")
		void upsertReview_createsSeparateReviewPerUser() {
			Product root = product(PRODUCT_ID, SELLER_ID, ProductStatus.ON_SALE);

			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(root));
			given(reviewRepository.findByUserIdAndProductId(BUYER_A, PRODUCT_ID)).willReturn(Optional.empty());
			given(reviewRepository.findByUserIdAndProductId(BUYER_B, PRODUCT_ID)).willReturn(Optional.empty());

			productReviewService.upsertReview(BUYER_A, PRODUCT_ID, 5);
			productReviewService.upsertReview(BUYER_B, PRODUCT_ID, 3);

			ArgumentCaptor<Review> captor = ArgumentCaptor.forClass(Review.class);
			then(reviewRepository).should(times(2)).save(captor.capture());
			assertThat(captor.getAllValues())
				.extracting(Review::getUserId)
				.containsExactly(BUYER_A, BUYER_B);
			assertThat(captor.getAllValues())
				.extracting(Review::getRating)
				.containsExactly((short) 5, (short) 3);
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
