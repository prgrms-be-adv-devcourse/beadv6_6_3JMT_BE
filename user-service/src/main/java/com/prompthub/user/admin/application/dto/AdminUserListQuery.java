package com.prompthub.user.admin.application.dto;

import com.prompthub.user.user.domain.model.UserRole;
import com.prompthub.user.user.domain.model.UserStatus;

public record AdminUserListQuery(
        UserStatus status,
        UserRole role,
        String keyword,
        int page,
        int size
) {
}
