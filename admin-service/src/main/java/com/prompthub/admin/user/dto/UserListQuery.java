package com.prompthub.admin.user.dto;

import com.prompthub.admin.user.entity.enums.UserRole;
import com.prompthub.admin.user.entity.enums.UserStatus;

public record UserListQuery(
	UserStatus status,
	UserRole role,
	String keyword,
	int page,
	int size
) {
}
