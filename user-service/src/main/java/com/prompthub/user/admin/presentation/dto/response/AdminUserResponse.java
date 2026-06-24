package com.prompthub.user.admin.presentation.dto.response;

import com.prompthub.user.admin.application.dto.AdminUserSummaryResult;
import com.prompthub.user.user.domain.model.UserStatus;

public record AdminUserResponse(
        String id,
        String name,
        String email,
        String role,
        String status
) {
    public static AdminUserResponse from(AdminUserSummaryResult result) {
        return new AdminUserResponse(
                result.userId().toString(),
                result.name(),
                result.email(),
                result.role().name().toLowerCase(),
                mapStatus(result.status())
        );
    }

    private static String mapStatus(UserStatus status) {
        return switch (status) {
            case ACTIVE -> "active";
            case BLOCKED -> "suspended";
            case WITHDRAWN -> "withdrawn";
        };
    }
}
