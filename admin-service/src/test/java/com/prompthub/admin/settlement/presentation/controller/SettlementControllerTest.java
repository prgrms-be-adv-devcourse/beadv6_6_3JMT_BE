package com.prompthub.admin.settlement.presentation.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.prompthub.admin.settlement.application.usecase.SettlementUseCase;
import com.prompthub.admin.settlement.presentation.dto.response.SettlementListResponse;
import java.util.List;
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
}
