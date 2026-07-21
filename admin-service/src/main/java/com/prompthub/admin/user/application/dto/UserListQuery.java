package com.prompthub.admin.user.application.dto;

import com.prompthub.admin.user.domain.model.UserRole;
import com.prompthub.admin.user.domain.model.UserStatus;

public record UserListQuery(
	UserStatus status,
	UserRole role,
	String keyword,
	int page,
	int size
) {
}
