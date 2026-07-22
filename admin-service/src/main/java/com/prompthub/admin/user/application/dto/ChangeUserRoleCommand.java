package com.prompthub.admin.user.application.dto;

import com.prompthub.admin.user.domain.model.UserRole;

import java.util.UUID;

public record ChangeUserRoleCommand(
	UUID userId,
	UserRole role
) {
}
