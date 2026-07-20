package com.prompthub.product.presentation.controller;

import com.prompthub.product.application.usecase.ProductInternalUseCase;
import com.prompthub.product.exception.ProductExceptionHandler;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.BDDMockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ReviewControllerTest {

	private static final UUID PRODUCT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID BUYER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

	private MockMvc mockMvc;

	@Mock
	private ProductInternalUseCase productInternalUseCase;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(new ReviewController(productInternalUseCase))
			.setControllerAdvice(new ProductExceptionHandler())
			.build();
	}

	@Test
	@DisplayName("별점을 등록/수정한다")
	void upsertReview_success() throws Exception {
		mockMvc.perform(post("/api/v2/products/{productId}/reviews", PRODUCT_ID)
				.header("X-User-Id", BUYER_ID.toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"rating\":5}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));

		verify(productInternalUseCase).upsertReview(BUYER_ID, PRODUCT_ID, 5);
	}

	@Test
	@DisplayName("rating이 1 미만이면 400을 반환한다")
	void upsertReview_ratingTooLow_badRequest() throws Exception {
		mockMvc.perform(post("/api/v2/products/{productId}/reviews", PRODUCT_ID)
				.header("X-User-Id", BUYER_ID.toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"rating\":0}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value("V001"));

		verifyNoInteractions(productInternalUseCase);
	}

	@Test
	@DisplayName("rating이 5 초과면 400을 반환한다")
	void upsertReview_ratingTooHigh_badRequest() throws Exception {
		mockMvc.perform(post("/api/v2/products/{productId}/reviews", PRODUCT_ID)
				.header("X-User-Id", BUYER_ID.toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"rating\":6}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value("V001"));

		verifyNoInteractions(productInternalUseCase);
	}

	@Test
	@DisplayName("X-User-Id 헤더가 없으면 500(SYS001)으로 처리된다 — 다른 컨트롤러들과 동일하게 헤더 누락을 별도 처리하지 않음")
	void upsertReview_missingUserIdHeader_internalServerError() throws Exception {
		mockMvc.perform(post("/api/v2/products/{productId}/reviews", PRODUCT_ID)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"rating\":5}"))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value("SYS001"));

		verifyNoInteractions(productInternalUseCase);
	}
}
