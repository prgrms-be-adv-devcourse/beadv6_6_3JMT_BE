package com.prompthub.user.user.presentation.dto.response;

import com.prompthub.user.user.application.dto.UserResult;
import com.prompthub.user.user.domain.model.UserRole;

import java.util.UUID;

public record UserProfileResponse(
        UUID id,
        String name,
        String email,
        String profileImageUrl,
        UserRole role
) {
    public static UserProfileResponse from(UserResult result) {
        return new UserProfileResponse(
                result.userId(),
                result.name(),
                result.email(),
                result.profileImageUrl(),
                result.role()
        );
    }
}
