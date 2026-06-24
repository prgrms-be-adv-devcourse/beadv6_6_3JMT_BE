package com.prompthub.user.admin.application.dto;

import com.prompthub.user.user.domain.model.User;
import com.prompthub.user.user.domain.model.UserRole;
import com.prompthub.user.user.domain.model.UserStatus;

import java.util.UUID;

public record AdminUserSummaryResult(
        UUID userId,
        String name,
        String email,
        UserRole role,
        UserStatus status
) {
    public static AdminUserSummaryResult from(User user) {
        return new AdminUserSummaryResult(
                user.getUserId(),
                user.getName(),
                user.getEmail(),
                user.getPrimaryRole(),
                user.getStatus()
        );
    }
}
