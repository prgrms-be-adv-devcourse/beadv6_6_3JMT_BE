package com.prompthub.admin.user.dto;

import com.prompthub.admin.user.entity.enums.UserRole;

import java.util.UUID;

public record ChangeUserRoleCommand(
	UUID userId,
	UserRole role
) {
}
