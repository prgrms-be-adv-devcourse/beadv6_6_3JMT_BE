package com.prompthub.admin.user.application.dto;

import com.prompthub.admin.user.domain.model.User;
import com.prompthub.admin.user.domain.model.UserRole;
import com.prompthub.admin.user.domain.model.UserStatus;

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
