package com.prompthub.user.auth.presentation.dto.response;

import com.prompthub.user.auth.application.dto.OAuthLoginResult;
import com.prompthub.user.user.domain.model.UserRole;

import java.time.Instant;
import java.util.UUID;

public record OAuthLoginResponse(
        UserInfo user,
        String accessToken,
        String refreshToken,
        String tokenType,
        Instant expiresAt,
        boolean isNewUser
) {
    public record UserInfo(UUID id, String name, String email, UserRole role) {}

    public static OAuthLoginResponse from(OAuthLoginResult result) {
        return new OAuthLoginResponse(
                new UserInfo(result.userId(), result.name(), result.email(), result.role()),
                result.accessToken(),
                result.refreshToken(),
                result.tokenType(),
                result.expiresAt(),
                result.isNewUser()
        );
    }
}
