package com.prompthub.admin.product.presentation.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import com.prompthub.admin.product.application.dto.AdminProductListQuery;
import com.prompthub.admin.product.application.dto.AdminProductPageResult;
import com.prompthub.admin.product.application.usecase.ProductUseCase;
import com.prompthub.admin.product.domain.model.enums.ProductStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProductController.class)
@ActiveProfiles("test")
class ProductControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ProductUseCase productUseCase;

	@Nested
	@DisplayName("GET /api/v2/admin/products")
	class ListProducts {

		@Test
		@DisplayName("파라미터가 없으면 ALL(null)·page=0·size=20으로 조회한다")
		void listProducts_defaults() throws Exception {
			given(productUseCase.listProducts(new AdminProductListQuery(null, null, 0, 20)))
				.willReturn(new AdminProductPageResult(List.of(), 0, 20, 0, false));

			mockMvc.perform(get("/api/v2/admin/products"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.meta.page").value(0))
				.andExpect(jsonPath("$.meta.size").value(20))
				.andExpect(jsonPath("$.meta.total").value(0))
				.andExpect(jsonPath("$.meta.hasNext").value(false));

			then(productUseCase).should().listProducts(new AdminProductListQuery(null, null, 0, 20));
		}

		@Test
		@DisplayName("status=pending_review는 PENDING_REVIEW 필터로 전달된다")
		void listProducts_statusPendingReview() throws Exception {
			given(productUseCase.listProducts(new AdminProductListQuery(ProductStatus.PENDING_REVIEW, null, 0, 20)))
				.willReturn(new AdminProductPageResult(List.of(), 0, 20, 0, false));

			mockMvc.perform(get("/api/v2/admin/products").param("status", "pending_review"))
				.andExpect(status().isOk());

			then(productUseCase).should()
				.listProducts(new AdminProductListQuery(ProductStatus.PENDING_REVIEW, null, 0, 20));
		}

		@Test
		@DisplayName("keyword는 그대로 전달된다")
		void listProducts_keyword() throws Exception {
			given(productUseCase.listProducts(new AdminProductListQuery(ProductStatus.ON_SALE, "프롬프트", 0, 20)))
				.willReturn(new AdminProductPageResult(List.of(), 0, 20, 0, false));

			mockMvc.perform(get("/api/v2/admin/products")
					.param("status", "on_sale")
					.param("keyword", "프롬프트"))
				.andExpect(status().isOk());

			then(productUseCase).should()
				.listProducts(new AdminProductListQuery(ProductStatus.ON_SALE, "프롬프트", 0, 20));
		}

		@Test
		@DisplayName("미인식 status 값은 400을 반환한다")
		void listProducts_invalidStatus_badRequest() throws Exception {
			mockMvc.perform(get("/api/v2/admin/products").param("status", "unknown"))
				.andExpect(status().isBadRequest());
		}
	}

	@Nested
	@DisplayName("PATCH /api/v2/admin/products/{productId}/approve")
	class ApproveProduct {

		@Test
		@DisplayName("승인 성공 시 200과 공통 응답 포맷을 반환한다")
		void approveProduct_success() throws Exception {
			UUID productId = UUID.randomUUID();

			mockMvc.perform(patch("/api/v2/admin/products/{productId}/approve", productId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true));

			then(productUseCase).should().approveProduct(productId);
		}
	}

	@Nested
	@DisplayName("PATCH /api/v2/admin/products/{productId}/reject")
	class RejectProduct {

		@Test
		@DisplayName("반려 성공 시 사유를 함께 전달한다")
		void rejectProduct_success() throws Exception {
			UUID productId = UUID.randomUUID();

			mockMvc.perform(patch("/api/v2/admin/products/{productId}/reject", productId)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"reason\":\"콘텐츠 미흡\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true));

			then(productUseCase).should().rejectProduct(productId, "콘텐츠 미흡");
		}
	}
}
