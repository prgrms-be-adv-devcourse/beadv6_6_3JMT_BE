package com.prompthub.admin.user.dto;

import com.prompthub.admin.user.entity.User;
import com.prompthub.admin.user.entity.enums.UserRole;

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
