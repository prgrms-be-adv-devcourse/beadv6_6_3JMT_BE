package com.prompthub.product.presentation.controller;

import com.prompthub.product.application.usecase.ProductInternalUseCase;
import com.prompthub.product.application.usecase.ProductQueryUseCase;
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.exception.enums.ProductErrorCode;
import com.prompthub.product.exception.ProductExceptionHandler;
import com.prompthub.product.presentation.dto.response.ProductDetailResponse;
import com.prompthub.product.presentation.dto.response.ProductListItemResponse;
import com.prompthub.product.presentation.dto.response.ProductReviewResponse;
import com.prompthub.product.presentation.dto.response.ProductVersionResponse;
import com.prompthub.product.presentation.dto.response.ProductsByIdsResponse;
import com.prompthub.presentation.dto.PageResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ProductControllerTest {

	private static final UUID PRODUCT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID SELLER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 5, 1, 0, 0);
	private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 6, 1, 0, 0);

	private MockMvc mockMvc;

	@Mock
	private ProductQueryUseCase productQueryUseCase;

	@Mock
	private ProductInternalUseCase productInternalUseCase;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(new ProductController(productQueryUseCase, productInternalUseCase))
			.setControllerAdvice(new ProductExceptionHandler())
			.build();
	}

	@Nested
	@DisplayName("GET /api/v2/products")
	class GetProducts {

		@Test
		@DisplayName("로그인 없이 상품 목록을 조회한다")
		void getProducts_success() throws Exception {
			ProductListItemResponse item = productListItemResponse(PRODUCT_ID, "PROMPT");
			given(productQueryUseCase.getProducts("react", "PROMPT", "popular", 1, 8))
				.willReturn(PageResponse.success(List.of(item), 1, 8, 1, false));

			mockMvc.perform(get("/api/v2/products")
					.param("q", "react")
					.param("productType", "PROMPT")
					.param("sort", "popular")
					.param("page", "1")
					.param("size", "8"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.message").value("success"))
				.andExpect(jsonPath("$.data[0].id").value(PRODUCT_ID.toString()))
				.andExpect(jsonPath("$.data[0].productType").value("PROMPT"))
				.andExpect(jsonPath("$.data[0].tags[0]").value("리액트"))
				.andExpect(jsonPath("$.meta.page").value(1))
				.andExpect(jsonPath("$.meta.size").value(8));
		}
	}

	@Nested
	@DisplayName("GET /api/v2/products/{productId}")
	class GetProduct {

		@Test
		@DisplayName("로그인 없이 상품 상세를 조회한다")
		void getProduct_success() throws Exception {
			ProductDetailResponse response = productDetailResponse();
			given(productQueryUseCase.getProduct(PRODUCT_ID)).willReturn(response);

			mockMvc.perform(get("/api/v2/products/{productId}", PRODUCT_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.id").value(PRODUCT_ID.toString()))
				.andExpect(jsonPath("$.data.title").value("리액트 컴포넌트 리팩터링 도우미"))
				.andExpect(jsonPath("$.data.productType").value("PROMPT"))
				.andExpect(jsonPath("$.data.tags[0]").value("리액트"))
				.andExpect(jsonPath("$.data.versions[0].ver").value("v1.3"));
		}

		@Test
		@DisplayName("UUID가 아니면 400 응답을 반환한다")
		void getProduct_invalidProductId() throws Exception {
			mockMvc.perform(get("/api/v2/products/not-uuid"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.code").value("V001"));

			verifyNoInteractions(productQueryUseCase);
		}

		@Test
		@DisplayName("상품이 없으면 P001 응답을 반환한다")
		void getProduct_notFound() throws Exception {
			given(productQueryUseCase.getProduct(PRODUCT_ID))
				.willThrow(new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));

			mockMvc.perform(get("/api/v2/products/{productId}", PRODUCT_ID))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.code").value("P001"));
		}
	}

	@Nested
	@DisplayName("GET /api/v2/products/{productId}/related")
	class GetRelatedProducts {

		@Test
		@DisplayName("로그인 없이 연관 상품을 조회한다")
		void getRelatedProducts_success() throws Exception {
			ProductListItemResponse item = productListItemResponse(PRODUCT_ID, "PROMPT");
			given(productQueryUseCase.getRelatedProducts(PRODUCT_ID, 4)).willReturn(List.of(item));

			mockMvc.perform(get("/api/v2/products/{productId}/related", PRODUCT_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data[0].id").value(PRODUCT_ID.toString()));
		}

		@Test
		@DisplayName("limit 값을 service에 전달한다")
		void getRelatedProducts_withLimit() throws Exception {
			given(productQueryUseCase.getRelatedProducts(PRODUCT_ID, 2)).willReturn(List.of());

			mockMvc.perform(get("/api/v2/products/{productId}/related", PRODUCT_ID)
					.param("limit", "2"))
				.andExpect(status().isOk());

			org.mockito.Mockito.verify(productQueryUseCase).getRelatedProducts(eq(PRODUCT_ID), eq(2));
		}
	}

	@Nested
	@DisplayName("GET /api/v2/products/by-ids")
	class GetProductsByIds {

		@Test
		@DisplayName("ids 파라미터로 여러 상품을 배치 조회한다")
		void getProductsByIds_success() throws Exception {
			UUID productId2 = UUID.fromString("55555555-5555-5555-5555-555555555555");
			ProductsByIdsResponse item = new ProductsByIdsResponse(
				PRODUCT_ID, SELLER_ID, "리액트 컴포넌트 리팩터링 도우미", 7900, null,
				"PROMPT", "GPT-4o", 760, 4.7, "ON_SALE"
			);
			given(productInternalUseCase.getProductsByIds(List.of(PRODUCT_ID, productId2)))
				.willReturn(List.of(item));

			mockMvc.perform(get("/api/v2/products/by-ids")
					.param("ids", PRODUCT_ID + "," + productId2))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data[0].productId").value(PRODUCT_ID.toString()))
				.andExpect(jsonPath("$.data[0].title").value("리액트 컴포넌트 리팩터링 도우미"));

			verify(productInternalUseCase).getProductsByIds(List.of(PRODUCT_ID, productId2));
		}
	}

	@Nested
	@DisplayName("GET /api/v2/products/{productId}/reviews")
	class GetProductReviews {

		@Test
		@DisplayName("로그인 없이 상품 리뷰를 조회한다")
		void getProductReviews_success() throws Exception {
			UUID reviewId = UUID.fromString("33333333-3333-3333-3333-333333333333");
			ProductReviewResponse review = new ProductReviewResponse(
				reviewId,
				UUID.fromString("44444444-4444-4444-4444-444444444444"),
				(short) 5,
				"좋아요",
				CREATED_AT,
				UPDATED_AT
			);
			given(productQueryUseCase.getProductReviews(PRODUCT_ID)).willReturn(List.of(review));

			mockMvc.perform(get("/api/v2/products/{productId}/reviews", PRODUCT_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data[0].id").value(reviewId.toString()))
				.andExpect(jsonPath("$.data[0].rating").value(5));
		}
	}

	private ProductListItemResponse productListItemResponse(UUID productId, String productType) {
		return new ProductListItemResponse(
			productId,
			"리액트 컴포넌트 리팩터링 도우미",
			productType,
			"GPT-4o",
			7900,
			null,
			4.7,
			760,
			"테스트판매자",
			SELLER_ID,
			null,
			"컴포넌트 분리, 상태 정리, 타입 개선",
			null,
			List.of("리액트", "리팩터링"),
			CREATED_AT,
			UPDATED_AT
		);
	}

	private ProductDetailResponse productDetailResponse() {
		return new ProductDetailResponse(
			PRODUCT_ID,
			"리액트 컴포넌트 리팩터링 도우미",
			"PROMPT",
			"GPT-4o",
			7900,
			4.7,
			760,
			SELLER_ID.toString(),
			SELLER_ID,
			null,
			0,
			null,
			"컴포넌트 분리, 상태 정리, 타입 개선",
			null,
			"[리액트 컴포넌트 리팩터링 도우미]\n\n전체 내용은 구매 후 확인할 수 있습니다.",
			List.of("리액트", "리팩터링"),
			List.of(new ProductVersionResponse("v1.3", "2026-06-01", "테스트 개선")),
			List.of(),
			CREATED_AT,
			UPDATED_AT
		);
	}
}
