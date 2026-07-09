package com.prompthub.product.presentation.controller;

import com.prompthub.product.application.usecase.ProductAdminUseCase;
import com.prompthub.product.exception.ProductException;
import com.prompthub.product.exception.ProductExceptionHandler;
import com.prompthub.product.exception.enums.ProductErrorCode;
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

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminProductControllerTest {

	private MockMvc mockMvc;

	@Mock
	private ProductAdminUseCase productAdminUseCase;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(new AdminProductController(productAdminUseCase))
			.setControllerAdvice(new ProductExceptionHandler())
			.build();
	}

	@Nested
	@DisplayName("GET /api/v1/admin/products")
	class GetPendingReviewProducts {

		@Test
		@DisplayName("ADMIN이 아니면 검수 대기 목록 조회를 거부한다")
		void getPendingReviewProducts_forbiddenForNonAdmin() throws Exception {
			given(productAdminUseCase.getPendingReviewProducts("BUYER"))
				.willThrow(new ProductException(ProductErrorCode.PRODUCT_FORBIDDEN));

			mockMvc.perform(get("/api/v1/admin/products").header("X-User-Role", "BUYER"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.code").value("P003"));
		}
	}

	@Nested
	@DisplayName("PATCH /api/v1/admin/products/{productId}/approve")
	class ApproveProduct {

		@Test
		@DisplayName("승인 성공 시 200과 공통 응답 포맷을 반환한다")
		void approveProduct_success() throws Exception {
			UUID productId = UUID.randomUUID();

			mockMvc.perform(patch("/api/v1/admin/products/{productId}/approve", productId)
					.header("X-User-Role", "ADMIN"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true));

			then(productAdminUseCase).should().approveProduct("ADMIN", productId);
		}
	}

	@Nested
	@DisplayName("PATCH /api/v1/admin/products/{productId}/reject")
	class RejectProduct {

		@Test
		@DisplayName("반려 성공 시 사유를 함께 전달한다")
		void rejectProduct_success() throws Exception {
			UUID productId = UUID.randomUUID();

			mockMvc.perform(patch("/api/v1/admin/products/{productId}/reject", productId)
					.header("X-User-Role", "ADMIN")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"reason\":\"콘텐츠 미흡\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true));

			then(productAdminUseCase).should().rejectProduct("ADMIN", productId, "콘텐츠 미흡");
		}
	}
}
