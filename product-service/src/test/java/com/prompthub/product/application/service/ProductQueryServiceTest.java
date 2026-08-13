package com.prompthub.product.application.service;
import com.prompthub.product.application.service.query.ProductQueryService;
import com.prompthub.product.application.service.query.ProductFamilyResolver;

import com.prompthub.product.application.gateway.external.ObjectStorageGateway;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.model.enums.ProductType;
import com.prompthub.product.domain.model.projection.ProductListProjection;
import com.prompthub.product.domain.model.projection.ProductReviewProjection;
import com.prompthub.product.domain.repository.ProductRepository;
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.exception.enums.ProductErrorCode;
import com.prompthub.product.presentation.dto.response.product.ProductDetailResponse;
import com.prompthub.product.presentation.dto.response.product.ProductListItemResponse;
import com.prompthub.product.presentation.dto.response.review.ProductReviewResponse;
import com.prompthub.product.presentation.dto.response.product.ProductsByIdsResponse;
import com.prompthub.recommendation.application.ProductRecommender;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import static com.prompthub.product.support.ProductContentFixtures.promptContent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class ProductQueryServiceTest {

	private static final UUID PRODUCT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID RECOMMENDED_PRODUCT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UUID SELLER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
	private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 5, 1, 0, 0);
	private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 6, 1, 0, 0);

	@Mock
	private ProductRepository productRepository;

	@Mock
	private ObjectStorageGateway objectStorage;

	@Mock
	private ProductRecommender productRecommender;

	private ProductQueryService productQueryService;

	@BeforeEach
	void setUp() {
		productQueryService = new ProductQueryService(
			productRepository, objectStorage, new ProductFamilyResolver(productRepository), productRecommender);
		lenient().when(productRepository.incrementViewCount(any(), any())).thenReturn(true);
		lenient().when(productRepository.getSalesCounts(any())).thenReturn(Map.of());
		lenient().when(productRepository.getAverageRatings(any())).thenReturn(Map.of());
		lenient().when(objectStorage.presignIfPresent(any())).thenCallRealMethod();
		lenient().when(objectStorage.presignAllIfPresent(any())).thenCallRealMethod();
	}

	@Nested
	@DisplayName("상품 상세 조회")
	class GetProduct {

		@Test
		@DisplayName("판매 중인 상품 상세를 조회한다")
		void getProduct_success() {
			Product product = product(ProductStatus.ON_SALE, null);
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));
			given(productRepository.findAllByFamilyRootIds(List.of(PRODUCT_ID))).willReturn(List.of(product));
			given(productRepository.getAverageRating(PRODUCT_ID)).willReturn(4.5);
			given(objectStorage.createPresignedGetUrl("https://cdn.example.com/images/1.jpg"))
				.willReturn("https://cdn.example.com/images/1.jpg?presigned");

			ProductDetailResponse response = productQueryService.getProduct(PRODUCT_ID);

			assertThat(response.id()).isEqualTo(PRODUCT_ID);
			assertThat(response.title()).isEqualTo("리액트 컴포넌트 리팩터링 도우미");
			assertThat(response.productType()).isEqualTo("PROMPT");
			assertThat(response.tags()).containsExactly("리액트", "리팩터링");
			assertThat(response.rating()).isEqualTo(4.5);
			assertThat(response.content()).contains("전체 내용은 구매 후 확인");
			assertThat(response.versions()).hasSize(1);
			assertThat(response.imageUrls()).containsExactly("https://cdn.example.com/images/1.jpg?presigned");
			assertThat(response.hasContext()).isTrue();
			assertThat(response.hasNuance()).isFalse();
			assertThat(response.checklistRecorded()).isTrue();
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
		@DisplayName("상세 조회 중 상품이 삭제되어 조회수 갱신이 실패하면 P001 예외가 발생한다")
		void getProduct_deletedBeforeViewCountUpdate() {
			Product product = product(ProductStatus.ON_SALE, null);
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));
			given(productRepository.findAllByFamilyRootIds(List.of(PRODUCT_ID))).willReturn(List.of(product));
			given(productRepository.incrementViewCount(eq(PRODUCT_ID), any())).willReturn(false);

			assertThatThrownBy(() -> productQueryService.getProduct(PRODUCT_ID))
				.isInstanceOf(ProductException.class)
				.satisfies(exception -> assertThat(((ProductException) exception).getErrorCode())
					.isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND));
		}

		@Test
		@DisplayName("판매 중이 아니면 공개 상세에서 노출하지 않는다")
		void getProduct_notOnSale() {
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product(ProductStatus.DRAFT, null)));

			assertThatThrownBy(() -> productQueryService.getProduct(PRODUCT_ID))
				.isInstanceOf(ProductException.class);
		}

		@Test
		@DisplayName("모든 필드가 정확한 위치에 채워진다 — 인접한 boolean·문자열 필드가 뒤바뀌면 이 테스트가 실패한다")
		void getProduct_fillsEveryFieldAtItsOwnPosition() {
			Product product = productWithDistinctValues();
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));
			given(productRepository.findAllByFamilyRootIds(List.of(PRODUCT_ID))).willReturn(List.of(product));
			given(productRepository.getAverageRating(PRODUCT_ID)).willReturn(4.5);
			given(productRepository.sumSalesCountByFamilyRootId(PRODUCT_ID)).willReturn(760L);
			given(productRepository.countOnSaleProductsBySellerId(SELLER_ID)).willReturn(3L);
			given(objectStorage.createPresignedGetUrl("products/thumb.jpg")).willReturn("https://cdn/presigned-thumb");
			given(objectStorage.createPresignedGetUrl("https://cdn.example.com/images/1.jpg"))
				.willReturn("https://cdn.example.com/images/1.jpg?presigned");

			ProductDetailResponse response = productQueryService.getProduct(PRODUCT_ID);

			assertThat(response.id()).isEqualTo(PRODUCT_ID);
			assertThat(response.title()).isEqualTo("리액트 컴포넌트 리팩터링 도우미");
			assertThat(response.productType()).isEqualTo("PROMPT");
			assertThat(response.model()).isEqualTo("GPT-4o");
			assertThat(response.amount()).isEqualTo(7900);
			assertThat(response.rating()).isEqualTo(4.5);
			assertThat(response.salesCount()).isEqualTo(760);
			assertThat(response.sellerId()).isEqualTo(SELLER_ID);
			assertThat(response.sellerProductCount()).isEqualTo(3);
			assertThat(response.desc()).isEqualTo("컴포넌트 분리, 상태 정리, 타입 개선");
			assertThat(response.thumbnail_url()).isEqualTo("https://cdn/presigned-thumb");
			assertThat(response.imageUrls()).containsExactly("https://cdn.example.com/images/1.jpg?presigned");
			assertThat(response.tags()).containsExactly("리액트", "리팩터링");
			assertThat(response.hasContext()).isTrue();
			assertThat(response.hasObjective()).isFalse();
			assertThat(response.hasNuance()).isTrue();
			assertThat(response.hasTone()).isFalse();
			assertThat(response.hasExamples()).isTrue();
			assertThat(response.hasExecution()).isFalse();
			assertThat(response.hasRoleAssignment()).isTrue();
			assertThat(response.checklistRecorded()).isTrue();
			assertThat(response.createdAt()).isEqualTo(CREATED_AT);
			// updatedAt 자리는 실제로 product.getUpdatedAt()이 아니라 조회 시각(viewedAt)이 채운다
			// (ProductQueryService.getProduct() 참고) — 고정값 대신 createdAt과 달라야 함만 확인한다.
			assertThat(response.updatedAt()).isAfter(CREATED_AT);
		}

		private Product productWithDistinctValues() {
			Product product = product(ProductStatus.ON_SALE, null);
			ReflectionTestUtils.setField(product, "thumbnailUrl", "products/thumb.jpg");
			ReflectionTestUtils.setField(product, "model", "GPT-4o");
			ReflectionTestUtils.setField(product, "hasContext", true);
			ReflectionTestUtils.setField(product, "hasObjective", false);
			ReflectionTestUtils.setField(product, "hasNuance", true);
			ReflectionTestUtils.setField(product, "hasTone", false);
			ReflectionTestUtils.setField(product, "hasExamples", true);
			ReflectionTestUtils.setField(product, "hasExecution", false);
			ReflectionTestUtils.setField(product, "hasRoleAssignment", true);
			ReflectionTestUtils.setField(product, "checklistRecorded", true);
			return product;
		}
	}

	@Nested
	@DisplayName("family resolution")
	class FamilyResolution {

		@Test
		@DisplayName("SUPERSEDED된 옛 id로 조회해도 family의 현재 ON_SALE row로 resolve한다")
		void getProduct_oldSupersededId_resolvesToCurrentOnSale() {
			UUID oldId = PRODUCT_ID;
			UUID currentId = RECOMMENDED_PRODUCT_ID;
			Product old = productFixture(oldId, null, ProductStatus.SUPERSEDED, (short) 1, (short) 0);
			Product current = productFixture(currentId, oldId, ProductStatus.ON_SALE, (short) 2, (short) 0);

			given(productRepository.findById(oldId)).willReturn(Optional.of(old));
			given(productRepository.findAllByFamilyRootIds(List.of(oldId))).willReturn(List.of(old, current));
			given(productRepository.getAverageRating(oldId)).willReturn(4.5);
			given(productRepository.countOnSaleProductsBySellerId(SELLER_ID)).willReturn(1L);

			ProductDetailResponse result = productQueryService.getProduct(oldId);

			assertThat(result.id()).isEqualTo(currentId);
			assertThat(result.versions()).hasSize(2);
		}

		@Test
		@DisplayName("상세 조회 시 salesCount를 family 전체 합산으로 반환한다")
		void getProduct_salesCount_isFamilySum() {
			UUID oldId = PRODUCT_ID;
			UUID currentId = RECOMMENDED_PRODUCT_ID;
			Product old = productFixture(oldId, null, ProductStatus.SUPERSEDED, (short) 1, (short) 0);
			Product current = productFixture(currentId, oldId, ProductStatus.ON_SALE, (short) 2, (short) 0);

			given(productRepository.findById(oldId)).willReturn(Optional.of(old));
			given(productRepository.findAllByFamilyRootIds(List.of(oldId))).willReturn(List.of(old, current));
			given(productRepository.getAverageRating(oldId)).willReturn(0.0);
			given(productRepository.sumSalesCountByFamilyRootId(oldId)).willReturn(42L);
			given(productRepository.countOnSaleProductsBySellerId(SELLER_ID)).willReturn(1L);

			ProductDetailResponse result = productQueryService.getProduct(oldId);

			assertThat(result.salesCount()).isEqualTo(42);
		}

		@Test
		@DisplayName("family에 ON_SALE row가 없으면 404를 던진다")
		void getProduct_noOnSaleInFamily_throwsNotFound() {
			Product rejected = productFixture(PRODUCT_ID, null, ProductStatus.REJECTED, (short) 1, (short) 0);
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(rejected));
			given(productRepository.findAllByFamilyRootIds(List.of(PRODUCT_ID))).willReturn(List.of(rejected));

			assertThatThrownBy(() -> productQueryService.getProduct(PRODUCT_ID))
				.isInstanceOf(ProductException.class);
		}
	}

	private Product productFixture(UUID id, UUID parentId, ProductStatus status, short majorVersion, short patchVersion) {
		Product product = Product.create(id, SELLER_ID, promptContent());
		ReflectionTestUtils.setField(product, "parentId", parentId);
		ReflectionTestUtils.setField(product, "status", status);
		ReflectionTestUtils.setField(product, "majorVersion", majorVersion);
		ReflectionTestUtils.setField(product, "patchVersion", patchVersion);
		ReflectionTestUtils.setField(product, "createdAt", CREATED_AT);
		ReflectionTestUtils.setField(product, "updatedAt", UPDATED_AT);
		return product;
	}

	@Nested
	@DisplayName("추천 상품 조회")
	class GetRecommendedProducts {

		@Test
		@DisplayName("추천기가 고른 상품을 표시용 정보로 채워 반환한다")
		void getRecommendedProducts_success() {
			Product product = product(ProductStatus.ON_SALE, null);
			Product recommended = product(ProductStatus.ON_SALE, null);
			ReflectionTestUtils.setField(recommended, "id", RECOMMENDED_PRODUCT_ID);
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));
			given(productRepository.findAllByFamilyRootIds(List.of(PRODUCT_ID))).willReturn(List.of(product));
			given(productRecommender.recommend(PRODUCT_ID, PRODUCT_ID, "PROMPT", 4))
				.willReturn(List.of(RECOMMENDED_PRODUCT_ID));
			given(productRepository.findProjectionsByIds(List.of(RECOMMENDED_PRODUCT_ID)))
				.willReturn(List.of(productListProjection(RECOMMENDED_PRODUCT_ID, "PROMPT")));
			given(productRepository.findAllByIdIn(List.of(RECOMMENDED_PRODUCT_ID)))
				.willReturn(List.of(recommended));

			List<ProductListItemResponse> response = productQueryService.getRecommendedProducts(PRODUCT_ID, 0);

			assertThat(response).hasSize(1);
			assertThat(response.getFirst().id()).isEqualTo(RECOMMENDED_PRODUCT_ID);
			then(productRecommender).should().recommend(PRODUCT_ID, PRODUCT_ID, "PROMPT", 4);
		}

		@Test
		@DisplayName("추천 순서를 그대로 유지한다 — 조회는 순서를 보장하지 않는다")
		void getRecommendedProducts_preservesOrder() {
			UUID second = UUID.fromString("33333333-3333-3333-3333-333333333333");
			Product product = product(ProductStatus.ON_SALE, null);
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));
			given(productRepository.findAllByFamilyRootIds(List.of(PRODUCT_ID))).willReturn(List.of(product));
			given(productRecommender.recommend(PRODUCT_ID, PRODUCT_ID, "PROMPT", 4))
				.willReturn(List.of(RECOMMENDED_PRODUCT_ID, second));
			// 조회가 역순으로 돌려줘도 추천 순서가 이겨야 한다.
			given(productRepository.findProjectionsByIds(List.of(RECOMMENDED_PRODUCT_ID, second)))
				.willReturn(List.of(
					productListProjection(second, "PROMPT"),
					productListProjection(RECOMMENDED_PRODUCT_ID, "PROMPT")));
			given(productRepository.findAllByIdIn(List.of(RECOMMENDED_PRODUCT_ID, second)))
				.willReturn(List.of());

			List<ProductListItemResponse> response = productQueryService.getRecommendedProducts(PRODUCT_ID, 4);

			assertThat(response).extracting(ProductListItemResponse::id)
				.containsExactly(RECOMMENDED_PRODUCT_ID, second);
		}

		@Test
		@DisplayName("추천 상품 각 필드가 정확한 위치에 채워진다 — 인접한 문자열·날짜 필드가 뒤바뀌면 이 테스트가 실패한다")
		void getRecommendedProducts_fillsEveryFieldAtItsOwnPosition() {
			Product product = product(ProductStatus.ON_SALE, null);
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));
			given(productRepository.findAllByFamilyRootIds(List.of(PRODUCT_ID))).willReturn(List.of(product));
			given(productRecommender.recommend(PRODUCT_ID, PRODUCT_ID, "PROMPT", 4))
				.willReturn(List.of(RECOMMENDED_PRODUCT_ID));
			given(productRepository.findProjectionsByIds(List.of(RECOMMENDED_PRODUCT_ID)))
				.willReturn(List.of(productListProjection(RECOMMENDED_PRODUCT_ID, "PROMPT")));
			given(productRepository.findAllByIdIn(List.of(RECOMMENDED_PRODUCT_ID))).willReturn(List.of());

			ProductListItemResponse response = productQueryService.getRecommendedProducts(PRODUCT_ID, 0).getFirst();

			assertThat(response.id()).isEqualTo(RECOMMENDED_PRODUCT_ID);
			assertThat(response.title()).isEqualTo("리액트 컴포넌트 리팩터링 도우미");
			assertThat(response.productType()).isEqualTo("PROMPT");
			assertThat(response.model()).isEqualTo("GPT-4o");
			assertThat(response.amount()).isEqualTo(7900);
			assertThat(response.rating()).isEqualTo(4.7);
			assertThat(response.salesCount()).isEqualTo(760);
			assertThat(response.sellerId()).isEqualTo(SELLER_ID);
			assertThat(response.desc()).isEqualTo("컴포넌트 분리, 상태 정리, 타입 개선");
			assertThat(response.thumbnail_url()).isNull();
			assertThat(response.tags()).isEmpty();
			assertThat(response.createdAt()).isEqualTo(CREATED_AT);
			assertThat(response.updatedAt()).isEqualTo(UPDATED_AT);
		}

		@Test
		@DisplayName("추천 결과가 없으면 조회하지 않고 빈 목록을 준다")
		void getRecommendedProducts_empty() {
			Product product = product(ProductStatus.ON_SALE, null);
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));
			given(productRepository.findAllByFamilyRootIds(List.of(PRODUCT_ID))).willReturn(List.of(product));
			given(productRecommender.recommend(PRODUCT_ID, PRODUCT_ID, "PROMPT", 4)).willReturn(List.of());

			assertThat(productQueryService.getRecommendedProducts(PRODUCT_ID, 4)).isEmpty();
			then(productRepository).should(org.mockito.Mockito.never()).findProjectionsByIds(any());
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
			given(productRepository.findAllByFamilyRootIds(List.of(PRODUCT_ID))).willReturn(List.of(product));
			given(productRepository.findActiveReviews(PRODUCT_ID)).willReturn(List.of(projection));

			List<ProductReviewResponse> response = productQueryService.getProductReviews(PRODUCT_ID);

			assertThat(response).hasSize(1);
			assertThat(response.getFirst().id()).isEqualTo(reviewId);
			assertThat(response.getFirst().rating()).isEqualTo((short) 5);
		}
	}

	@Nested
	@DisplayName("찜 상품 배치 조회")
	class GetProductsByIds {

		@Test
		@DisplayName("thumbnailUrl을 presigned 다운로드 URL로 변환해 반환한다")
		void getProductsByIds_presignsThumbnailUrl() {
			Product product = product(ProductStatus.ON_SALE, null);
			ReflectionTestUtils.setField(product, "thumbnailUrl", "products/1/thumbnail/uuid.jpg");
			given(productRepository.findAllByIdIn(List.of(PRODUCT_ID))).willReturn(List.of(product));
			given(productRepository.findAllByFamilyRootIds(List.of(PRODUCT_ID))).willReturn(List.of(product));
			given(objectStorage.createPresignedGetUrl("products/1/thumbnail/uuid.jpg"))
				.willReturn("https://s3/presigned-thumbnail");

			List<ProductsByIdsResponse> result = productQueryService.getProductsByIds(List.of(PRODUCT_ID));

			assertThat(result).hasSize(1);
			assertThat(result.get(0).thumbnailUrl()).isEqualTo("https://s3/presigned-thumbnail");
		}

		@Test
		@DisplayName("thumbnailUrl이 없으면 presign을 시도하지 않는다")
		void getProductsByIds_noThumbnail_skipsPresign() {
			Product product = product(ProductStatus.ON_SALE, null);
			given(productRepository.findAllByIdIn(List.of(PRODUCT_ID))).willReturn(List.of(product));
			given(productRepository.findAllByFamilyRootIds(List.of(PRODUCT_ID))).willReturn(List.of(product));

			List<ProductsByIdsResponse> result = productQueryService.getProductsByIds(List.of(PRODUCT_ID));

			assertThat(result.get(0).thumbnailUrl()).isNull();
			then(objectStorage).should(never()).createPresignedGetUrl(any());
		}

		@ParameterizedTest(name = "요청 {0}건")
		@ValueSource(ints = {1, 10, 100})
		@DisplayName("요청 건수와 무관하게 family 판매량과 평점을 각각 한 번만 조회한다")
		void getProductsByIds_aggregatesEachFamilyOnce(int requestCount) {
			List<UUID> requestedIds = Collections.nCopies(requestCount, PRODUCT_ID);
			Product product = product(ProductStatus.ON_SALE, null);
			given(productRepository.findAllByIdIn(requestedIds)).willReturn(List.of(product));
			given(productRepository.findAllByFamilyRootIds(List.of(PRODUCT_ID))).willReturn(List.of(product));
			given(productRepository.getSalesCounts(List.of(PRODUCT_ID))).willReturn(Map.of(PRODUCT_ID, 42L));
			given(productRepository.getAverageRatings(List.of(PRODUCT_ID))).willReturn(Map.of(PRODUCT_ID, 4.8));

			List<ProductsByIdsResponse> result = productQueryService.getProductsByIds(requestedIds);

			assertThat(result).hasSize(requestCount).allSatisfy(response -> {
				assertThat(response.productId()).isEqualTo(PRODUCT_ID);
				assertThat(response.salesCount()).isEqualTo(42);
				assertThat(response.averageRating()).isEqualTo(4.8);
			});
			then(productRepository).should(times(1)).getSalesCounts(List.of(PRODUCT_ID));
			then(productRepository).should(times(1)).getAverageRatings(List.of(PRODUCT_ID));
		}

		@Test
		@DisplayName("빈 요청은 빈 결과를 반환하고 family 상품을 조회하지 않는다")
		void getProductsByIds_emptyRequest() {
			List<ProductsByIdsResponse> result = productQueryService.getProductsByIds(List.of());

			assertThat(result).isEmpty();
			then(productRepository).should(never()).findAllByFamilyRootIds(any());
			then(productRepository).should(times(1)).getSalesCounts(List.of());
			then(productRepository).should(times(1)).getAverageRatings(List.of());
		}
	}

	private ProductListProjection productListProjection(UUID productId, String productType) {
		return new ProductListProjection(
			productId,
			"리액트 컴포넌트 리팩터링 도우미",
			productType,
			"GPT-4o",
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
		Product product = instantiate(Product.class);
		ReflectionTestUtils.setField(product, "id", PRODUCT_ID);
		ReflectionTestUtils.setField(product, "sellerId", SELLER_ID);
		ReflectionTestUtils.setField(product, "majorVersion", (short) 1);
		ReflectionTestUtils.setField(product, "patchVersion", (short) 3);
		ReflectionTestUtils.setField(product, "changeReason", "테스트 개선");
		ReflectionTestUtils.setField(product, "name", "리액트 컴포넌트 리팩터링 도우미");
		ReflectionTestUtils.setField(product, "description", "컴포넌트 분리, 상태 정리, 타입 개선");
		ReflectionTestUtils.setField(product, "productType", ProductType.PROMPT);
		ReflectionTestUtils.setField(product, "amount", 7900);
		ReflectionTestUtils.setField(product, "status", status);
		ReflectionTestUtils.setField(product, "salesCount", 760);
		ReflectionTestUtils.setField(product, "tags", List.of("리액트", "리팩터링"));
		ReflectionTestUtils.setField(product, "imageUrls", List.of("https://cdn.example.com/images/1.jpg"));
		ReflectionTestUtils.setField(product, "hasContext", true);
		ReflectionTestUtils.setField(product, "hasNuance", false);
		ReflectionTestUtils.setField(product, "checklistRecorded", true);
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
