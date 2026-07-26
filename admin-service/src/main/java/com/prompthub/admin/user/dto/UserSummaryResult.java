package com.prompthub.admin.user.dto;

import com.prompthub.admin.user.entity.User;
import com.prompthub.admin.user.entity.enums.UserRole;
import com.prompthub.admin.user.entity.enums.UserStatus;

import java.util.UUID;

public record UserSummaryResult(
	UUID userId,
	String name,
	String email,
	UserRole role,
	UserStatus status
) {
	public static UserSummaryResult from(User user) {
		return new UserSummaryResult(
			user.getUserId(),
			user.getName(),
			user.getEmail(),
			user.getPrimaryRole(),
			user.getStatus()
		);
	}
}
