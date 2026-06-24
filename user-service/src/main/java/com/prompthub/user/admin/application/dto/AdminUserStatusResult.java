package com.prompthub.user.admin.application.dto;

import com.prompthub.user.user.domain.model.User;
import com.prompthub.user.user.domain.model.UserStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record AdminUserStatusResult(
        UUID userId,
        UserStatus status,
        LocalDateTime updatedAt
) {
    public static AdminUserStatusResult from(User user) {
        return new AdminUserStatusResult(
                user.getUserId(),
                user.getStatus(),
                user.getUpdatedAt()
        );
    }
}
