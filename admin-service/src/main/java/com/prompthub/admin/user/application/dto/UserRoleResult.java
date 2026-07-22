package com.prompthub.admin.user.application.dto;

import com.prompthub.admin.user.domain.model.User;
import com.prompthub.admin.user.domain.model.UserRole;

import java.time.LocalDateTime;
import java.util.UUID;

public record UserRoleResult(
	UUID userId,
	UserRole role,
	LocalDateTime updatedAt
) {
	public static UserRoleResult from(User user) {
		return new UserRoleResult(
			user.getUserId(),
			user.getPrimaryRole(),
			user.getUpdatedAt()
		);
	}
}
