package com.prompthub.admin.seller.presentation.controller;

import com.prompthub.admin.seller.application.dto.SellerRegisterPageResult;
import com.prompthub.admin.seller.application.dto.SellerRegisterReviewResult;
import com.prompthub.admin.seller.application.dto.SellerRegisterSummaryResult;
import com.prompthub.admin.seller.application.usecase.SellerUseCase;
import com.prompthub.admin.seller.domain.model.SellerRegisterStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SellerController.class)
@ActiveProfiles("test")
class SellerControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private SellerUseCase sellerUseCase;

	@Test
	void 판매자_신청_목록을_조회한다() throws Exception {
		SellerRegisterSummaryResult summary = new SellerRegisterSummaryResult(
			UUID.fromString("00000000-0000-0000-0000-000000000101"),
			UUID.fromString("00000000-0000-0000-0000-000000000102"),
			"이서아", "seoah@example.com", "이미지 생성 전문",
			List.of("이미지 생성"), null, SellerRegisterStatus.PENDING,
			LocalDateTime.of(2026, 6, 14, 0, 0));
		when(sellerUseCase.listSellerRegisters(any()))
			.thenReturn(new SellerRegisterPageResult(List.of(summary), 1, 20, 1, false));

		mockMvc.perform(get("/api/v2/admin/sellers/register").param("status", "ALL"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].name").value("이서아"))
			.andExpect(jsonPath("$.data[0].status").value("pending"));
	}

	@Test
	void 판매자_신청을_승인한다() throws Exception {
		UUID registerId = UUID.fromString("00000000-0000-0000-0000-000000000201");
		UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000202");
		when(sellerUseCase.approve(any())).thenReturn(new SellerRegisterReviewResult(
			registerId, userId, SellerRegisterStatus.APPROVED, null,
			LocalDateTime.of(2026, 7, 20, 10, 0)));

		mockMvc.perform(patch("/api/v2/admin/sellers/register/{registerId}/approve", registerId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("approved"));
	}

	@Test
	void 판매자_신청을_반려한다() throws Exception {
		UUID registerId = UUID.fromString("00000000-0000-0000-0000-000000000301");
		UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000302");
		when(sellerUseCase.reject(any())).thenReturn(new SellerRegisterReviewResult(
			registerId, userId, SellerRegisterStatus.REJECTED, "사유 불충분",
			LocalDateTime.of(2026, 7, 20, 10, 0)));

		mockMvc.perform(patch("/api/v2/admin/sellers/register/{registerId}/reject", registerId)
				.contentType("application/json")
				.content("{\"rejectReason\":\"사유 불충분\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("rejected"))
			.andExpect(jsonPath("$.data.rejectReason").value("사유 불충분"));
	}

	@Test
	void 반려_사유가_비어있으면_400을_내려준다() throws Exception {
		UUID registerId = UUID.fromString("00000000-0000-0000-0000-000000000401");

		mockMvc.perform(patch("/api/v2/admin/sellers/register/{registerId}/reject", registerId)
				.contentType("application/json")
				.content("{\"rejectReason\":\"\"}"))
			.andExpect(status().isBadRequest());
	}
}
