package com.prompthub.product.presentation.controller;

import com.prompthub.product.application.usecase.ProductAdminUseCase;
import com.prompthub.product.exception.ProductExceptionHandler;
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
	@DisplayName("GET /api/v2/admin/products")
	class GetPendingReviewProducts {

		@Test
		@DisplayName("검수 대기 목록을 조회한다 (역할 검사는 gateway 소관)")
		void getPendingReviewProducts_success() throws Exception {
			given(productAdminUseCase.getPendingReviewProducts()).willReturn(List.of());

			mockMvc.perform(get("/api/v2/admin/products"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true));
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

			then(productAdminUseCase).should().approveProduct(productId);
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

			then(productAdminUseCase).should().rejectProduct(productId, "콘텐츠 미흡");
		}
	}
}
