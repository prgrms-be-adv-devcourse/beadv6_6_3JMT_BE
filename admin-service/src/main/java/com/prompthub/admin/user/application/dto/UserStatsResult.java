package com.prompthub.admin.user.application.dto;

public record UserStatsResult(
	long totalUsers,
	long todayNewUsers
) {
}
