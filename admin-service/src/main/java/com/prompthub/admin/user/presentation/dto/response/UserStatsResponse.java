package com.prompthub.admin.user.presentation.dto.response;

import com.prompthub.admin.user.application.dto.UserStatsResult;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "회원 통계 응답")
public record UserStatsResponse(
	@Schema(description = "누적 회원 수", example = "1240")
	long totalUsers,
	@Schema(description = "오늘 신규 가입 수", example = "13")
	long todayNewUsers
) {
	public static UserStatsResponse from(UserStatsResult result) {
		return new UserStatsResponse(result.totalUsers(), result.todayNewUsers());
	}
}
