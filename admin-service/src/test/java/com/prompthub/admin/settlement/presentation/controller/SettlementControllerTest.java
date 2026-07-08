package com.prompthub.admin.settlement.presentation.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.prompthub.admin.global.exception.AdminErrorCode;
import com.prompthub.admin.global.exception.AdminException;
import com.prompthub.admin.settlement.application.usecase.SettlementUseCase;
import com.prompthub.admin.settlement.domain.exception.SettlementInvalidStateException;
import com.prompthub.admin.settlement.domain.model.enums.PayoutStatus;
import com.prompthub.admin.settlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.admin.settlement.domain.model.enums.SettlementStatus;
import com.prompthub.admin.settlement.presentation.dto.response.SettlementListResponse;
import com.prompthub.admin.settlement.presentation.dto.response.SettlementResponse;
import com.prompthub.admin.settlement.presentation.dto.response.SettlementStatusResponse;
import com.prompthub.admin.settlement.presentation.dto.response.SettlementSummaryResponse;
import com.prompthub.admin.settlement.presentation.dto.response.SettlementSummaryResponse.Card;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SettlementController.class)
@ActiveProfiles("test")
class SettlementControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private SettlementUseCase settlementUseCase;

	@Test
	void 정산_목록을_페이징으로_내려준다() throws Exception {
		when(settlementUseCase.getList(any()))
			.thenReturn(new SettlementListResponse(List.of(), 0L, 0, 20));

		mockMvc.perform(get("/api/v1/admin/settlements"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.totalElements").value(0))
			.andExpect(jsonPath("$.data.page").value(0))
			.andExpect(jsonPath("$.data.size").value(20));
	}

	@Test
	void 잘못된_상태값은_400_을_내려준다() throws Exception {
		mockMvc.perform(get("/api/v1/admin/settlements").param("status", "NOPE"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("A-001"));
	}

	@Test
	void 정산_요약_카드를_내려준다() throws Exception {
		when(settlementUseCase.getSummary())
			.thenReturn(new SettlementSummaryResponse(
				List.of(new Card(SettlementDisplayStatus.WAITING.name(), BigDecimal.TEN, 4L))));

		mockMvc.perform(get("/api/v1/admin/settlements/summary"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.cards").isArray())
			.andExpect(jsonPath("$.data.cards[0].status").value("WAITING"));
	}

	@Test
	void 정산을_승인하면_변경된_상태를_내려준다() throws Exception {
		UUID settlementId = UUID.randomUUID();
		UUID actorId = UUID.randomUUID();
		when(settlementUseCase.approve(settlementId))
			.thenReturn(new SettlementStatusResponse(
				settlementId,
				SettlementStatus.APPROVED,
				PayoutStatus.READY,
				SettlementDisplayStatus.APPROVED,
				LocalDateTime.now(),
				null,
				null,
				null,
				LocalDateTime.now()));

		mockMvc.perform(patch("/api/v1/admin/settlements/{settlementId}/approve", settlementId)
				.header("X-User-Id", actorId.toString()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.settlementStatus").value("APPROVED"));
	}

	@Test
	void 정산을_취소하면_취소된_표시상태를_내려준다() throws Exception {
		UUID settlementId = UUID.randomUUID();
		UUID actorId = UUID.randomUUID();
		when(settlementUseCase.cancel(settlementId))
			.thenReturn(new SettlementResponse(
				settlementId, UUID.randomUUID(), "CANCELLED", LocalDateTime.now()));

		mockMvc.perform(patch("/api/v1/admin/settlements/{settlementId}/cancel", settlementId)
				.header("X-User-Id", actorId.toString()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.displayStatus").value("CANCELLED"));
	}

	@Test
	void 존재하지_않는_정산을_승인하면_404_를_내려준다() throws Exception {
		UUID settlementId = UUID.randomUUID();
		UUID actorId = UUID.randomUUID();
		when(settlementUseCase.approve(settlementId))
			.thenThrow(new AdminException(AdminErrorCode.SETTLEMENT_NOT_FOUND));

		mockMvc.perform(patch("/api/v1/admin/settlements/{settlementId}/approve", settlementId)
				.header("X-User-Id", actorId.toString()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("A-003"));
	}

	@Test
	void 전이_불가능한_상태에서_승인하면_409_를_내려준다() throws Exception {
		UUID settlementId = UUID.randomUUID();
		UUID actorId = UUID.randomUUID();
		when(settlementUseCase.approve(settlementId))
			.thenThrow(new SettlementInvalidStateException(
				"approve", SettlementStatus.CANCELLED, PayoutStatus.NOT_READY));

		mockMvc.perform(patch("/api/v1/admin/settlements/{settlementId}/approve", settlementId)
				.header("X-User-Id", actorId.toString()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("A-004"));
	}
}
