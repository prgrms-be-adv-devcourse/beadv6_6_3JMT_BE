package com.prompthub.admin.settlement.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.prompthub.admin.global.exception.AdminErrorCode;
import com.prompthub.admin.global.exception.AdminException;
import com.prompthub.admin.settlement.application.dto.SettlementListQuery;
import com.prompthub.admin.settlement.application.dto.SettlementWeeklyListQuery;
import com.prompthub.admin.settlement.application.usecase.SettlementUseCase;
import com.prompthub.admin.settlement.domain.exception.SettlementInvalidStateException;
import com.prompthub.admin.settlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.admin.settlement.presentation.dto.response.SettlementDetailResponse;
import com.prompthub.admin.settlement.presentation.dto.response.SettlementListResponse;
import com.prompthub.admin.settlement.presentation.dto.response.SettlementResponse;
import com.prompthub.admin.settlement.presentation.dto.response.SettlementStatusResponse;
import com.prompthub.admin.settlement.presentation.dto.response.SettlementSummaryResponse;
import com.prompthub.admin.settlement.presentation.dto.response.SettlementSummaryResponse.Card;
import com.prompthub.admin.settlement.presentation.dto.response.SettlementWeeklyListResponse;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
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

	private static final UUID SELLER_ID =
		UUID.fromString("22222222-2222-2222-2222-222222222222");

	@Test
	void 어드민_월별목록은_v2경로와_기본20을_유지한다() throws Exception {
		when(settlementUseCase.getList(any()))
			.thenReturn(new SettlementListResponse(List.of(), 0L, 0, 20));

		mockMvc.perform(get("/api/v2/admin/settlements")
				.param("settlementMonth", "2026-07"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.totalElements").value(0))
			.andExpect(jsonPath("$.data.page").value(0))
			.andExpect(jsonPath("$.data.size").value(20));

		ArgumentCaptor<SettlementListQuery> queryCaptor =
			ArgumentCaptor.forClass(SettlementListQuery.class);
		verify(settlementUseCase).getList(queryCaptor.capture());
		assertThat(queryCaptor.getValue().settlementMonth())
			.isEqualTo(YearMonth.of(2026, 7));
		assertThat(queryCaptor.getValue().size()).isEqualTo(20);
	}

	@Test
	void 어드민_주간목록은_상태와_월을_주간필터로_전달한다() throws Exception {
		when(settlementUseCase.getWeeklyList(any()))
			.thenReturn(new SettlementWeeklyListResponse(List.of(), List.of(), 0L, 0, 20));

		mockMvc.perform(get("/api/v2/admin/settlements/weeks")
				.param("status", "PAYOUT_REQUESTED")
				.param("settlementMonth", "2026-07"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.totalElements").value(0))
			.andExpect(jsonPath("$.data.size").value(20));

		ArgumentCaptor<SettlementWeeklyListQuery> queryCaptor =
			ArgumentCaptor.forClass(SettlementWeeklyListQuery.class);
		verify(settlementUseCase).getWeeklyList(queryCaptor.capture());
		assertThat(queryCaptor.getValue().status())
			.isEqualTo(SettlementDisplayStatus.PAYOUT_REQUESTED);
		assertThat(queryCaptor.getValue().settlementMonth())
			.isEqualTo(YearMonth.of(2026, 7));
		assertThat(queryCaptor.getValue().size()).isEqualTo(20);
	}

	@Test
	void 어드민_판매자월_상세를_조회한다() throws Exception {
		when(settlementUseCase.getDetail(SELLER_ID, YearMonth.of(2026, 7)))
			.thenReturn(emptyDetail(SELLER_ID, "2026-07"));

		mockMvc.perform(get(
				"/api/v2/admin/settlements/sellers/{sellerId}/months/2026-07",
				SELLER_ID))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.sellerId").value(SELLER_ID.toString()))
			.andExpect(jsonPath("$.data.settlementMonth").value("2026-07"));
	}

	@Test
	void 잘못된_상태값은_400_을_내려준다() throws Exception {
		mockMvc.perform(get("/api/v2/admin/settlements").param("status", "NOPE"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("A-001"));
	}

	@Test
	void 어드민_summary는_선택월을_받는다() throws Exception {
		when(settlementUseCase.getSummary(YearMonth.of(2026, 7)))
			.thenReturn(new SettlementSummaryResponse(
				List.of(new Card(SettlementDisplayStatus.WAITING.name(), BigDecimal.TEN, 4L))));

		mockMvc.perform(get("/api/v2/admin/settlements/summary")
				.param("settlementMonth", "2026-07"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.cards").isArray())
			.andExpect(jsonPath("$.data.cards[0].status").value("WAITING"));
	}

	@ParameterizedTest
	@CsvSource({"page,-1", "size,0", "size,101"})
	void 잘못된_페이지요청은_400이다(String name, String value) throws Exception {
		mockMvc.perform(get("/api/v2/admin/settlements").param(name, value))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("A-001"));
	}

	@Test
	void 정산을_승인하면_변경된_표시상태를_내려준다() throws Exception {
		UUID settlementId = UUID.randomUUID();
		UUID actorId = UUID.randomUUID();
		when(settlementUseCase.approve(settlementId))
			.thenReturn(new SettlementStatusResponse(
				settlementId,
				SettlementDisplayStatus.APPROVED,
				LocalDateTime.now(),
				null,
				null,
				LocalDateTime.now()));

		mockMvc.perform(patch("/api/v2/admin/settlements/{settlementId}/approve", settlementId)
				.header("X-User-Id", actorId.toString()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.displayStatus").value("APPROVED"));
	}

	@Test
	void 정산을_취소하면_취소된_표시상태를_내려준다() throws Exception {
		UUID settlementId = UUID.randomUUID();
		UUID actorId = UUID.randomUUID();
		when(settlementUseCase.cancel(settlementId))
			.thenReturn(new SettlementResponse(
				settlementId, UUID.randomUUID(), "CANCELLED", LocalDateTime.now()));

		mockMvc.perform(patch("/api/v2/admin/settlements/{settlementId}/cancel", settlementId)
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

		mockMvc.perform(patch("/api/v2/admin/settlements/{settlementId}/approve", settlementId)
				.header("X-User-Id", actorId.toString()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("A-003"));
	}

	@Test
	void 전이_불가능한_상태에서_승인하면_409_를_내려준다() throws Exception {
		UUID settlementId = UUID.randomUUID();
		UUID actorId = UUID.randomUUID();
		when(settlementUseCase.approve(settlementId))
			.thenThrow(new SettlementInvalidStateException("approve", SettlementDisplayStatus.CANCELLED));

		mockMvc.perform(patch("/api/v2/admin/settlements/{settlementId}/approve", settlementId)
				.header("X-User-Id", actorId.toString()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("A-004"));
	}

	private static SettlementDetailResponse emptyDetail(
		UUID sellerId, String settlementMonth) {
		return new SettlementDetailResponse(
			sellerId, null, settlementMonth, 0, 0, 0,
			BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
			List.of(), List.of());
	}
}
