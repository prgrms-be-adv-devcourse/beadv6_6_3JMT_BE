package com.prompthub.product.application.service;

import com.prompthub.product.application.client.SellerClient;
import com.prompthub.product.application.client.SellerInfo;
import com.prompthub.product.domain.model.entity.Category;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.model.projection.ProductListProjection;
import com.prompthub.product.domain.model.projection.ProductReviewProjection;
import com.prompthub.product.domain.repository.ProductRepository;
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.exception.enums.ProductErrorCode;
import com.prompthub.product.presentation.dto.response.ProductDetailResponse;
import com.prompthub.product.presentation.dto.response.ProductListItemResponse;
import com.prompthub.product.presentation.dto.response.ProductReviewResponse;
import com.prompthub.presentation.dto.PageResponse;
import java.time.LocalDateTime;
import java.util.List;
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
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class ProductQueryServiceTest {

	private static final UUID PRODUCT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID RELATED_PRODUCT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UUID SELLER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
	private static final UUID CATEGORY_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
	private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 5, 1, 0, 0);
	private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 6, 1, 0, 0);

	@Mock
	private ProductRepository productRepository;

	@Mock
	private SellerClient sellerClient;

	@InjectMocks
	private ProductQueryService productQueryService;

	@Nested
	@DisplayName("상품 목록 조회")
	class GetProducts {

		@Test
		@DisplayName("검색, 카테고리, 정렬, 페이징 조건으로 공개 상품 목록을 조회한다")
		void getProducts_success() {
			ProductListProjection projection = productListProjection(PRODUCT_ID, "coding");
			given(productRepository.findPublicProducts("react", "coding", "popular", Pageable.ofSize(8)))
				.willReturn(List.of(projection));
			given(productRepository.countPublicProducts("react", "coding"))
				.willReturn(1L);
			given(sellerClient.getSellerInfo(SELLER_ID))
				.willReturn(new SellerInfo(SELLER_ID, "테스트판매자", null, "ACTIVE"));

			PageResponse<ProductListItemResponse> response = productQueryService.getProducts(
				" React ",
				"coding",
				"unknown",
				0,
				8
			);

			assertThat(response.success()).isTrue();
			assertThat(response.data()).hasSize(1);
			assertThat(response.data().getFirst().id()).isEqualTo(PRODUCT_ID);
			assertThat(response.data().getFirst().category()).isEqualTo("coding");
			assertThat(response.meta().page()).isEqualTo(1);
			assertThat(response.meta().size()).isEqualTo(8);
			assertThat(response.meta().total()).isEqualTo(1);
			assertThat(response.meta().hasNext()).isFalse();
		}

		@Test
		@DisplayName("page와 size는 1 이상으로 보정한다")
		void getProducts_normalizesPageAndSize() {
			ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
			given(productRepository.findPublicProducts(
				org.mockito.ArgumentMatchers.anyString(),
				org.mockito.ArgumentMatchers.anyString(),
				org.mockito.ArgumentMatchers.anyString(),
				pageableCaptor.capture()
			)).willReturn(List.of());
			given(productRepository.countPublicProducts("", "all")).willReturn(0L);

			productQueryService.getProducts(null, null, "popular", -1, -10);

			assertThat(pageableCaptor.getValue().getPageNumber()).isZero();
			assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(1);
		}
	}

	@Nested
	@DisplayName("상품 상세 조회")
	class GetProduct {

		@Test
		@DisplayName("판매 중인 상품 상세를 조회한다")
		void getProduct_success() {
			Product product = product(ProductStatus.ON_SALE, null);
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));
			given(productRepository.getAverageRating(PRODUCT_ID)).willReturn(4.5);
			given(sellerClient.getSellerInfo(SELLER_ID))
				.willReturn(new SellerInfo(SELLER_ID, "테스트판매자", null, "ACTIVE"));

			ProductDetailResponse response = productQueryService.getProduct(PRODUCT_ID);

			assertThat(response.id()).isEqualTo(PRODUCT_ID);
			assertThat(response.title()).isEqualTo("리액트 컴포넌트 리팩터링 도우미");
			assertThat(response.category()).isEqualTo("coding");
			assertThat(response.rating()).isEqualTo(4.5);
			assertThat(response.seller()).isEqualTo("테스트판매자");
			assertThat(response.content()).contains("전체 내용은 구매 후 확인");
			assertThat(response.versions()).hasSize(1);
		}

		@Test
		@DisplayName("상품이 없으면 P001 예외가 발생한다")
		void getProduct_notFound() {
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.empty());

			assertThatThrownBy(() -> productQueryService.getProduct(PRODUCT_ID))
				.isInstanceOf(ProductException.class)
				.satisfies(exception ->
					assertThat(((ProductException) exception).getErrorCode())
						.isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND)
				);
		}

		@Test
		@DisplayName("판매 중이 아니면 공개 상세에서 노출하지 않는다")
		void getProduct_notOnSale() {
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product(ProductStatus.DRAFT, null)));

			assertThatThrownBy(() -> productQueryService.getProduct(PRODUCT_ID))
				.isInstanceOf(ProductException.class);
		}
	}

	@Nested
	@DisplayName("연관 상품 조회")
	class GetRelatedProducts {

		@Test
		@DisplayName("동일 카테고리의 판매 중인 연관 상품을 조회한다")
		void getRelatedProducts_success() {
			Product product = product(ProductStatus.ON_SALE, null);
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));
			given(productRepository.findRelatedProducts(PRODUCT_ID, CATEGORY_ID, 4))
				.willReturn(List.of(productListProjection(RELATED_PRODUCT_ID, "coding")));
			given(sellerClient.getSellerInfo(SELLER_ID))
				.willReturn(new SellerInfo(SELLER_ID, "테스트판매자", null, "ACTIVE"));

			List<ProductListItemResponse> response = productQueryService.getRelatedProducts(PRODUCT_ID, 0);

			assertThat(response).hasSize(1);
			assertThat(response.getFirst().id()).isEqualTo(RELATED_PRODUCT_ID);
			then(productRepository).should().findRelatedProducts(PRODUCT_ID, CATEGORY_ID, 4);
		}
	}

	@Nested
	@DisplayName("상품 리뷰 조회")
	class GetProductReviews {

		@Test
		@DisplayName("판매 중인 상품의 활성 리뷰를 조회한다")
		void getProductReviews_success() {
			UUID reviewId = UUID.fromString("55555555-5555-5555-5555-555555555555");
			Product product = product(ProductStatus.ON_SALE, null);
			ProductReviewProjection projection = new ProductReviewProjection(
				reviewId,
				UUID.fromString("66666666-6666-6666-6666-666666666666"),
				(short) 5,
				"좋아요",
				CREATED_AT,
				UPDATED_AT
			);
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));
			given(productRepository.findActiveReviews(PRODUCT_ID)).willReturn(List.of(projection));

			List<ProductReviewResponse> response = productQueryService.getProductReviews(PRODUCT_ID);

			assertThat(response).hasSize(1);
			assertThat(response.getFirst().id()).isEqualTo(reviewId);
			assertThat(response.getFirst().rating()).isEqualTo((short) 5);
		}
	}

	private ProductListProjection productListProjection(UUID productId, String category) {
		return new ProductListProjection(
			productId,
			"리액트 컴포넌트 리팩터링 도우미",
			category,
			"coding",
			"code-xml",
			"PROMPT",
			7900,
			4.7,
			760,
			SELLER_ID,
			"컴포넌트 분리, 상태 정리, 타입 개선",
			null,
			CREATED_AT,
			UPDATED_AT
		);
	}

	private Product product(ProductStatus status, LocalDateTime deletedAt) {
		Category category = instantiate(Category.class);
		ReflectionTestUtils.setField(category, "id", CATEGORY_ID);
		ReflectionTestUtils.setField(category, "name", "coding");

		Product product = instantiate(Product.class);
		ReflectionTestUtils.setField(product, "id", PRODUCT_ID);
		ReflectionTestUtils.setField(product, "sellerId", SELLER_ID);
		ReflectionTestUtils.setField(product, "category", category);
		ReflectionTestUtils.setField(product, "categoryId", CATEGORY_ID);
		ReflectionTestUtils.setField(product, "majorVersion", (short) 1);
		ReflectionTestUtils.setField(product, "patchVersion", (short) 3);
		ReflectionTestUtils.setField(product, "changeReason", "테스트 개선");
		ReflectionTestUtils.setField(product, "name", "리액트 컴포넌트 리팩터링 도우미");
		ReflectionTestUtils.setField(product, "description", "컴포넌트 분리, 상태 정리, 타입 개선");
		ReflectionTestUtils.setField(product, "productType", "PROMPT");
		ReflectionTestUtils.setField(product, "amount", 7900);
		ReflectionTestUtils.setField(product, "status", status);
		ReflectionTestUtils.setField(product, "salesCount", 760);
		ReflectionTestUtils.setField(product, "createdAt", CREATED_AT);
		ReflectionTestUtils.setField(product, "updatedAt", UPDATED_AT);
		ReflectionTestUtils.setField(product, "deletedAt", deletedAt);
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
