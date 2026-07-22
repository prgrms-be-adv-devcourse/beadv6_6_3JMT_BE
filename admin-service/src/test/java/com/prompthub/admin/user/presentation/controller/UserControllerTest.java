package com.prompthub.admin.user.presentation.controller;

import com.prompthub.admin.user.application.dto.UserPageResult;
import com.prompthub.admin.user.application.dto.UserRoleResult;
import com.prompthub.admin.user.application.dto.UserStatsResult;
import com.prompthub.admin.user.application.dto.UserStatusResult;
import com.prompthub.admin.user.application.dto.UserSummaryResult;
import com.prompthub.admin.user.application.usecase.UserUseCase;
import com.prompthub.admin.user.domain.model.UserRole;
import com.prompthub.admin.user.domain.model.UserStatus;
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

@WebMvcTest(UserController.class)
@ActiveProfiles("test")
class UserControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private UserUseCase userUseCase;

	@Test
	void 회원_목록을_조회한다() throws Exception {
		UserSummaryResult summary = new UserSummaryResult(
			UUID.fromString("00000000-0000-0000-0000-000000000001"),
			"김도윤", "doyoon.kim@gmail.com", UserRole.BUYER, UserStatus.ACTIVE);
		when(userUseCase.listUsers(any())).thenReturn(new UserPageResult(List.of(summary), 1, 20, 1, false));

		mockMvc.perform(get("/api/v2/admin/users").param("status", "ALL").param("role", "ALL"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].name").value("김도윤"))
			.andExpect(jsonPath("$.data[0].role").value("buyer"))
			.andExpect(jsonPath("$.meta.total").value(1));
	}

	@Test
	void 회원_통계를_조회한다() throws Exception {
		when(userUseCase.getUserStats()).thenReturn(new UserStatsResult(1240L, 13L));

		mockMvc.perform(get("/api/v2/admin/stats/users"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totalUsers").value(1240))
			.andExpect(jsonPath("$.data.todayNewUsers").value(13));
	}

	@Test
	void 사용자_상태를_변경한다() throws Exception {
		UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000002");
		when(userUseCase.changeUserStatus(any())).thenReturn(
			new UserStatusResult(userId, UserStatus.BLOCKED, LocalDateTime.of(2026, 7, 20, 10, 0)));

		mockMvc.perform(patch("/api/v2/admin/users/{userId}/status", userId)
				.contentType("application/json")
				.content("{\"status\":\"suspended\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("suspended"));
	}

	@Test
	void 알수없는_상태_문자열은_400을_내려준다() throws Exception {
		UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000003");

		mockMvc.perform(patch("/api/v2/admin/users/{userId}/status", userId)
				.contentType("application/json")
				.content("{\"status\":\"ALL\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("A-001"));
	}

	@Test
	void 사용자_역할을_변경한다() throws Exception {
		UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000004");
		when(userUseCase.changeUserRole(any())).thenReturn(
			new UserRoleResult(userId, UserRole.SELLER, LocalDateTime.of(2026, 7, 21, 10, 0)));

		mockMvc.perform(patch("/api/v2/admin/users/{userId}/role", userId)
				.contentType("application/json")
				.content("{\"role\":\"seller\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.role").value("seller"));
	}

	@Test
	void 알수없는_역할_문자열은_400을_내려준다() throws Exception {
		UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000005");

		mockMvc.perform(patch("/api/v2/admin/users/{userId}/role", userId)
				.contentType("application/json")
				.content("{\"role\":\"ALL\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("A-001"));
	}
}
