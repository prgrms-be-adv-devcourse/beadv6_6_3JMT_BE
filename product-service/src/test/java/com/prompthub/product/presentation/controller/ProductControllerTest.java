package com.prompthub.product.presentation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prompthub.product.application.usecase.ProductInternalUseCase;
import com.prompthub.product.application.usecase.ProductQueryUseCase;
import com.prompthub.product.application.usecase.ProductSellerUseCase;
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.exception.enums.ProductErrorCode;
import com.prompthub.product.exception.ProductExceptionHandler;
import com.prompthub.product.presentation.dto.response.ProductCreateResponse;
import com.prompthub.product.presentation.dto.response.ProductDetailResponse;
import com.prompthub.product.presentation.dto.response.ProductListItemResponse;
import com.prompthub.product.presentation.dto.response.ProductReviewResponse;
import com.prompthub.product.presentation.dto.response.ProductVersionResponse;
import com.prompthub.product.presentation.dto.response.ProductsByIdsResponse;
import com.prompthub.product.presentation.dto.response.SellerProductDetailResponse;
import com.prompthub.product.presentation.dto.response.SellerProductListItemResponse;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ProductControllerTest {

	private static final UUID PRODUCT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID SELLER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 5, 1, 0, 0);
	private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 6, 1, 0, 0);

	private MockMvc mockMvc;
	private ObjectMapper objectMapper;

	@Mock
	private ProductQueryUseCase productQueryUseCase;

	@Mock
	private ProductInternalUseCase productInternalUseCase;

	@Mock
	private ProductSellerUseCase productSellerUseCase;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(
				new ProductController(productQueryUseCase, productInternalUseCase, productSellerUseCase))
			.setControllerAdvice(new ProductExceptionHandler())
			.build();
		objectMapper = new ObjectMapper();
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
	@DisplayName("POST /api/v2/products")
	class CreateProduct {

		@Test
		@DisplayName("판매자가 상품을 등록한다")
		void createProduct_success() throws Exception {
			ProductCreateResponse response = new ProductCreateResponse(
				PRODUCT_ID, SELLER_ID, "리액트 컴포넌트 리팩터링 도우미", "PROMPT", "GPT-4o",
				"컴포넌트 분리, 상태 정리, 타입 개선", 7900, "DRAFT", CREATED_AT
			);
			given(productSellerUseCase.createProduct(eq(SELLER_ID), org.mockito.ArgumentMatchers.any()))
				.willReturn(response);

			mockMvc.perform(post("/api/v2/products")
					.contentType(MediaType.APPLICATION_JSON)
					.header("X-User-Id", SELLER_ID.toString())
					.content("""
						{"title":"리액트 컴포넌트 리팩터링 도우미","productType":"PROMPT","model":"GPT-4o",
						"desc":"컴포넌트 분리, 상태 정리, 타입 개선","amount":7900}
						"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.productId").value(PRODUCT_ID.toString()));
		}
	}

	@Nested
	@DisplayName("GET /api/v2/products/sellers/me")
	class GetMyProducts {

		@Test
		@DisplayName("판매자 본인 상품 목록을 조회한다")
		void getMyProducts_success() throws Exception {
			SellerProductListItemResponse item = new SellerProductListItemResponse(
				PRODUCT_ID, "리액트 컴포넌트 리팩터링 도우미", "PROMPT", "GPT-4o", 7900,
				"ON_SALE", 760, 4.5, "https://cdn.example.com/images/thumb.jpg", null, CREATED_AT, UPDATED_AT
			);
			given(productSellerUseCase.getMyProducts(SELLER_ID)).willReturn(List.of(item));

			mockMvc.perform(get("/api/v2/products/sellers/me")
					.header("X-User-Id", SELLER_ID.toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data[0].productId").value(PRODUCT_ID.toString()))
				.andExpect(jsonPath("$.data[0].averageRating").value(4.5));
		}
	}

	@Nested
	@DisplayName("GET /api/v2/products/sellers/me/summary")
	class GetMyProductSummary {

		@Test
		@DisplayName("등록 상품 수와 누적 판매 수를 반환한다")
		void getMyProductSummary_success() throws Exception {
			given(productInternalUseCase.getProductCount(SELLER_ID))
				.willReturn(new com.prompthub.product.presentation.dto.response.ProductCountResponse(SELLER_ID, 3, 42));

			mockMvc.perform(get("/api/v2/products/sellers/me/summary")
					.header("X-User-Id", SELLER_ID.toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.productCount").value(3))
				.andExpect(jsonPath("$.data.salesCount").value(42));
		}
	}

	@Nested
	@DisplayName("POST /api/v2/products/wishlists")
	class GetProductsByIds {

		@Test
		@DisplayName("productId 목록으로 여러 상품을 배치 조회한다")
		void getProductsByIds_success() throws Exception {
			UUID productId2 = UUID.fromString("55555555-5555-5555-5555-555555555555");
			ProductsByIdsResponse item = new ProductsByIdsResponse(
				PRODUCT_ID, SELLER_ID, "리액트 컴포넌트 리팩터링 도우미", 7900, null,
				"PROMPT", "GPT-4o", 760, 4.7, "ON_SALE"
			);
			given(productInternalUseCase.getProductsByIds(List.of(PRODUCT_ID, productId2)))
				.willReturn(List.of(item));

			mockMvc.perform(post("/api/v2/products/wishlists")
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(
						new com.prompthub.product.presentation.dto.request.ProductsByIdsRequest(
							List.of(PRODUCT_ID, productId2)))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data[0].productId").value(PRODUCT_ID.toString()))
				.andExpect(jsonPath("$.data[0].title").value("리액트 컴포넌트 리팩터링 도우미"));

			verify(productInternalUseCase).getProductsByIds(List.of(PRODUCT_ID, productId2));
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
				.andExpect(jsonPath("$.data.versions[0].ver").value("v1.3"))
				.andExpect(jsonPath("$.data.imageUrls[0]").value("https://cdn.example.com/images/1.jpg"));
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
	@DisplayName("PATCH /api/v2/products/{productId}")
	class UpdateProduct {

		@Test
		@DisplayName("판매자가 상품을 수정한다")
		void updateProduct_success() throws Exception {
			mockMvc.perform(patch("/api/v2/products/{productId}", PRODUCT_ID)
					.contentType(MediaType.APPLICATION_JSON)
					.header("X-User-Id", SELLER_ID.toString())
					.content("""
						{"title":"리액트 컴포넌트 리팩터링 도우미","productType":"PROMPT","model":"GPT-4o",
						"desc":"컴포넌트 분리, 상태 정리, 타입 개선","amount":7900}
						"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true));

			verify(productSellerUseCase).updateProduct(eq(SELLER_ID), eq(PRODUCT_ID), org.mockito.ArgumentMatchers.any());
		}
	}

	@Nested
	@DisplayName("DELETE /api/v2/products/{productId}")
	class DeleteProduct {

		@Test
		@DisplayName("판매자가 상품을 삭제/판매중단한다")
		void deleteProduct_success() throws Exception {
			mockMvc.perform(delete("/api/v2/products/{productId}", PRODUCT_ID)
					.header("X-User-Id", SELLER_ID.toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true));

			verify(productSellerUseCase).deleteProduct(SELLER_ID, PRODUCT_ID);
		}
	}

	@Nested
	@DisplayName("GET /api/v2/products/{productId}/recommends")
	class GetRelatedProducts {

		@Test
		@DisplayName("로그인 없이 연관 상품을 조회한다")
		void getRelatedProducts_success() throws Exception {
			ProductListItemResponse item = productListItemResponse(PRODUCT_ID, "PROMPT");
			given(productQueryUseCase.getRelatedProducts(PRODUCT_ID, 4)).willReturn(List.of(item));

			mockMvc.perform(get("/api/v2/products/{productId}/recommends", PRODUCT_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data[0].id").value(PRODUCT_ID.toString()));
		}

		@Test
		@DisplayName("limit 값을 service에 전달한다")
		void getRelatedProducts_withLimit() throws Exception {
			given(productQueryUseCase.getRelatedProducts(PRODUCT_ID, 2)).willReturn(List.of());

			mockMvc.perform(get("/api/v2/products/{productId}/recommends", PRODUCT_ID)
					.param("limit", "2"))
				.andExpect(status().isOk());

			org.mockito.Mockito.verify(productQueryUseCase).getRelatedProducts(eq(PRODUCT_ID), eq(2));
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

	@Nested
	@DisplayName("PATCH /api/v2/products/{productId}/inspection")
	class SubmitForReview {

		@Test
		@DisplayName("판매자가 검수를 요청한다")
		void submitForReview_success() throws Exception {
			mockMvc.perform(patch("/api/v2/products/{productId}/inspection", PRODUCT_ID)
					.header("X-User-Id", SELLER_ID.toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true));

			verify(productSellerUseCase).submitForReview(SELLER_ID, PRODUCT_ID);
		}
	}

	@Nested
	@DisplayName("GET /api/v2/products/{productId}/sellers/me")
	class GetMyProduct {

		@Test
		@DisplayName("판매자 본인 상품 상세를 조회한다")
		void getMyProduct_success() throws Exception {
			SellerProductDetailResponse response = new SellerProductDetailResponse(
				PRODUCT_ID, "리액트 컴포넌트 리팩터링 도우미", "PROMPT", "GPT-4o", 7900,
				"컴포넌트 분리, 상태 정리, 타입 개선", "본문 내용", null, null, "DRAFT", "1.0", 4.5,
				"https://cdn.example.com/images/thumb.jpg", List.of(), List.of("리액트"), null, List.of()
			);
			given(productSellerUseCase.getMyProduct(SELLER_ID, PRODUCT_ID)).willReturn(response);

			mockMvc.perform(get("/api/v2/products/{productId}/sellers/me", PRODUCT_ID)
					.header("X-User-Id", SELLER_ID.toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.productId").value(PRODUCT_ID.toString()))
				.andExpect(jsonPath("$.data.averageRating").value(4.5));
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
			SELLER_ID,
			0,
			null,
			"컴포넌트 분리, 상태 정리, 타입 개선",
			null,
			List.of("https://cdn.example.com/images/1.jpg"),
			"[리액트 컴포넌트 리팩터링 도우미]\n\n전체 내용은 구매 후 확인할 수 있습니다.",
			List.of("리액트", "리팩터링"),
			List.of(new ProductVersionResponse("v1.3", "2026-06-01", "테스트 개선")),
			List.of(),
			CREATED_AT,
			UPDATED_AT
		);
	}
}
