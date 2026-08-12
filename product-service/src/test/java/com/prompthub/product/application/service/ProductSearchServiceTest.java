package com.prompthub.product.application.service;

import com.prompthub.presentation.dto.PageResponse;
import com.prompthub.product.application.gateway.external.ObjectStorageGateway;
import com.prompthub.product.application.service.query.ProductSearchService;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.projection.ProductListProjection;
import com.prompthub.product.domain.repository.ProductRepository;
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.exception.enums.ProductErrorCode;
import com.prompthub.product.presentation.dto.response.product.ProductListItemResponse;
import com.prompthub.search.application.query.ProductSearchHit;
import com.prompthub.search.application.query.ProductSearchPageResult;
import com.prompthub.search.application.query.ProductSearchQueryPort;
import com.prompthub.search.application.query.ProductSearchUnavailableException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class ProductSearchServiceTest {

	private static final UUID PRODUCT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID SELLER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
	private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 5, 1, 0, 0);
	private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 6, 1, 0, 0);

	@Mock
	private ProductRepository productRepository;

	@Mock
	private ObjectStorageGateway objectStorage;

	@Mock
	private ProductSearchQueryPort productSearchQueryService;

	@Mock
	private Product product;

	private ProductSearchService productSearchService;

	@BeforeEach
	void setUp() {
		productSearchService = new ProductSearchService(productRepository, objectStorage, productSearchQueryService);
		lenient().when(productSearchQueryService.search(any(), any(), any(), any()))
			.thenThrow(new ProductSearchUnavailableException("테스트 기본값: ES 실패"));
		lenient().when(objectStorage.presignIfPresent(any())).thenCallRealMethod();
	}

	@Nested
	@DisplayName("상품 목록 조회")
	class GetProducts {

		@Test
		@DisplayName("검색, 상품유형, 정렬, 페이징 조건으로 공개 상품 목록을 조회한다")
		void getProducts_success() {
			given(productRepository.findPublicProducts("react", "PROMPT", "popular", Pageable.ofSize(8)))
				.willReturn(List.of(productListProjection()));
			given(productRepository.countPublicProducts("react", "PROMPT")).willReturn(1L);
			given(productRepository.findAllByIdIn(List.of(PRODUCT_ID))).willReturn(List.of(product));
			given(product.getId()).willReturn(PRODUCT_ID);
			given(product.getTags()).willReturn(List.of("리액트", "리팩터링"));

			PageResponse<ProductListItemResponse> response =
				productSearchService.getProducts(" React ", "PROMPT", "unknown", 0, 8);

			assertThat(response.data()).singleElement().satisfies(item -> {
				assertThat(item.id()).isEqualTo(PRODUCT_ID);
				assertThat(item.tags()).containsExactly("리액트", "리팩터링");
			});
			assertThat(response.meta().size()).isEqualTo(8);
			assertThat(response.meta().total()).isEqualTo(1);
		}

		@Test
		@DisplayName("page는 0 이상, size는 1 이상으로 보정한다")
		void getProducts_normalizesPageAndSize() {
			ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
			given(productRepository.findPublicProducts(any(), any(), any(), pageableCaptor.capture()))
				.willReturn(List.of());
			given(productRepository.countPublicProducts("", "all")).willReturn(0L);

			productSearchService.getProducts(null, null, "popular", -1, -10);

			assertThat(pageableCaptor.getValue().getPageNumber()).isZero();
			assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(1);
		}

		@Test
		@DisplayName("존재하지 않는 productType이면 예외가 발생한다")
		void getProducts_invalidProductType() {
			assertThatThrownBy(() -> productSearchService.getProducts("", "NOT_A_TYPE", "popular", 1, 20))
				.isInstanceOf(ProductException.class)
				.satisfies(exception -> assertThat(((ProductException) exception).getErrorCode())
					.isEqualTo(ProductErrorCode.INVALID_PRODUCT_TYPE));
		}

		@Test
		@DisplayName("ES 조회가 성공하면 ES 결과를 반환하고 RDB는 호출하지 않는다")
		void getProducts_esSuccess_mapsEsHitsWithoutTouchingRdb() {
			ProductSearchHit hit = new ProductSearchHit(
				PRODUCT_ID, SELLER_ID, "ES에서 온 상품", "ES 설명", "PROMPT", "GPT-4o",
				5000, "products/thumb.jpg", List.of("es태그"), 10, 4.2, CREATED_AT, UPDATED_AT);
			doReturn(new ProductSearchPageResult(List.of(hit), 1))
				.when(productSearchQueryService).search("es검색어", "PROMPT", "popular", PageRequest.of(0, 20));
			given(objectStorage.createPresignedGetUrl("products/thumb.jpg")).willReturn("https://s3/presigned");

			PageResponse<ProductListItemResponse> response =
				productSearchService.getProducts("es검색어", "PROMPT", "popular", 0, 20);

			assertThat(response.data()).singleElement().satisfies(item -> {
				assertThat(item.title()).isEqualTo("ES에서 온 상품");
				assertThat(item.thumbnail_url()).isEqualTo("https://s3/presigned");
			});
			then(productRepository).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("ES 조회가 실패하면 기존 RDB 경로로 폴백한다")
		void getProducts_esFailure_fallsBackToRdb() {
			given(productRepository.findPublicProducts("", "all", "popular", Pageable.ofSize(20)))
				.willReturn(List.of(productListProjection()));
			given(productRepository.countPublicProducts("", "all")).willReturn(1L);
			given(productRepository.findAllByIdIn(List.of(PRODUCT_ID))).willReturn(List.of(product));
			given(product.getId()).willReturn(PRODUCT_ID);
			given(product.getTags()).willReturn(List.of());

			PageResponse<ProductListItemResponse> response =
				productSearchService.getProducts(null, null, "popular", 0, 20);

			assertThat(response.data()).extracting(ProductListItemResponse::id).containsExactly(PRODUCT_ID);
		}

		@Test
		@DisplayName("검색 인프라 예외가 아니면 RDB 폴백으로 숨기지 않는다")
		void getProducts_programmingErrorDoesNotFallBack() {
			doReturn(new ProductSearchPageResult(null, 0))
				.when(productSearchQueryService).search("error", "all", "popular", PageRequest.of(0, 20));

			assertThatThrownBy(() -> productSearchService.getProducts("error", "all", "popular", 0, 20))
				.isInstanceOf(NullPointerException.class);
			then(productRepository).shouldHaveNoInteractions();
		}
	}

	@Nested
	@DisplayName("상품명 자동완성")
	class Suggest {

		@Test
		@DisplayName("검색어를 정규화해 ES 제안을 조회한다")
		void suggest_delegatesToElasticsearch() {
			given(productSearchQueryService.suggest("프롬", 5)).willReturn(List.of("프롬프트 마스터 팩"));

			assertThat(productSearchService.suggest("  프롬  ")).containsExactly("프롬프트 마스터 팩");
		}

		@Test
		@DisplayName("빈 검색어면 ES를 조회하지 않고 빈 목록을 반환한다")
		void suggest_blankKeywordSkipsQuery() {
			assertThat(productSearchService.suggest("   ")).isEmpty();
			then(productSearchQueryService).should(never()).suggest(any(), anyInt());
		}

		@Test
		@DisplayName("ES 조회가 실패해도 빈 목록을 반환한다")
		void suggest_returnsEmptyWhenElasticsearchFails() {
			willThrow(new ProductSearchUnavailableException("ES down"))
				.given(productSearchQueryService).suggest("프롬", 5);

			assertThat(productSearchService.suggest("프롬")).isEmpty();
		}
	}

	private ProductListProjection productListProjection() {
		return new ProductListProjection(
			PRODUCT_ID, "리액트 컴포넌트", "PROMPT", "GPT-4o", 7900, 4.7, 760,
			SELLER_ID, "설명", null, CREATED_AT, UPDATED_AT);
	}
}
