package com.prompthub.admin.user.dto;

public record UserStatsResult(
	long totalUsers,
	long todayNewUsers
) {
}
