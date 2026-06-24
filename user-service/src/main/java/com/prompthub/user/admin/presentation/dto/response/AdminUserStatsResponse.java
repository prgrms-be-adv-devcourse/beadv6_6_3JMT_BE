package com.prompthub.user.admin.presentation.dto.response;

import com.prompthub.user.admin.application.dto.AdminUserStatsResult;

public record AdminUserStatsResponse(
        long totalUsers,
        long todayNewUsers
) {
    public static AdminUserStatsResponse from(AdminUserStatsResult result) {
        return new AdminUserStatsResponse(result.totalUsers(), result.todayNewUsers());
    }
}
