package com.prompthub.user.user.application.dto;

import com.prompthub.user.user.domain.model.User;
import com.prompthub.user.user.domain.model.UserRole;

import java.util.UUID;

public record UserResult(
        UUID userId,
        String name,
        String email,
        String profileImageUrl,
        UserRole role
) {
    public static UserResult from(User user) {
        return new UserResult(
                user.getUserId(),
                user.getName(),
                user.getEmail(),
                user.getProfileImageUrl(),
                user.getRole()
        );
    }
}
