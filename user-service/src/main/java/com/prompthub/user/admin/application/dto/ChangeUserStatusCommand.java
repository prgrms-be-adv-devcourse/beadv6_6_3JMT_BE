package com.prompthub.user.admin.application.dto;

import com.prompthub.user.user.domain.model.UserStatus;

import java.util.UUID;

public record ChangeUserStatusCommand(
        UUID userId,
        UserStatus status
) {
}
