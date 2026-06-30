package com.prompthub.user.admin.application.dto;

public record AdminUserStatsResult(
        long totalUsers,
        long todayNewUsers
) {
}
