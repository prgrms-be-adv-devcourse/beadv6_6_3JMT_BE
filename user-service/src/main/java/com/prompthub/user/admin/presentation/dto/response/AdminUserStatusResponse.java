package com.prompthub.user.admin.presentation.dto.response;

import com.prompthub.user.admin.application.dto.AdminUserStatusResult;
import com.prompthub.user.user.domain.model.UserStatus;

import java.time.LocalDateTime;

public record AdminUserStatusResponse(
        String id,
        String status,
        LocalDateTime updatedAt
) {
    public static AdminUserStatusResponse from(AdminUserStatusResult result) {
        return new AdminUserStatusResponse(
                result.userId().toString(),
                mapStatus(result.status()),
                result.updatedAt()
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
