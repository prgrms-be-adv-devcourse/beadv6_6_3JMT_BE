package com.prompthub.user.user.presentation.dto.request;

import com.prompthub.user.user.application.dto.UpdateProfileCommand;

import java.util.UUID;

public record UpdateProfileRequest(
        String name,
        String email,
        String password
) {
    public UpdateProfileCommand toCommand(UUID userId) {
        return new UpdateProfileCommand(userId, name, email);
    }
}
