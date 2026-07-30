package com.prompthub.user.auth.application.dto;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.prompthub.user.user.domain.model.UserRole;

public record OAuthLoginCompletedResult(
        UUID userId,
        String name,
        String email,
        Set<UserRole> roles,
        String accessToken,
        String refreshToken,
        String tokenType,
        Instant expiresAt,
        boolean isNewUser
) implements OAuthLoginResult {

    @Override
    public OAuthLoginStatus loginStatus() {
        return OAuthLoginStatus.COMPLETED;
    }
}
