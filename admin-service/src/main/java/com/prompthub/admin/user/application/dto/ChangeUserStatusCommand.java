package com.prompthub.admin.user.application.dto;

import com.prompthub.admin.user.domain.model.UserStatus;

import java.util.UUID;

public record ChangeUserStatusCommand(
	UUID userId,
	UserStatus status
) {
}
