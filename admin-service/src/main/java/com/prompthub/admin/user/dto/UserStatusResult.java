package com.prompthub.admin.user.dto;

import com.prompthub.admin.user.entity.User;
import com.prompthub.admin.user.entity.enums.UserStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record UserStatusResult(
	UUID userId,
	UserStatus status,
	LocalDateTime updatedAt
) {
	public static UserStatusResult from(User user) {
		return new UserStatusResult(
			user.getUserId(),
			user.getStatus(),
			user.getUpdatedAt()
		);
	}
}
