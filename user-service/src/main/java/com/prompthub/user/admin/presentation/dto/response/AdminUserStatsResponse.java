package com.prompthub.user.admin.presentation.dto.response;

import com.prompthub.user.admin.application.dto.AdminUserStatsResult;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "회원 통계 응답")
public record AdminUserStatsResponse(
        @Schema(description = "누적 회원 수", example = "1240")
        long totalUsers,
        @Schema(description = "오늘 신규 가입 수", example = "13")
        long todayNewUsers
) {
    public static AdminUserStatsResponse from(AdminUserStatsResult result) {
        return new AdminUserStatsResponse(result.totalUsers(), result.todayNewUsers());
    }
}
