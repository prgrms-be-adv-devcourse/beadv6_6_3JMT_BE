package com.prompthub.admin.user.dto;

import com.prompthub.admin.user.entity.enums.UserStatus;

import java.util.UUID;

public record ChangeUserStatusCommand(
	UUID userId,
	UserStatus status
) {
}
