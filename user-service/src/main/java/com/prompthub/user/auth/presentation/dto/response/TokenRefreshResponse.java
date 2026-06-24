package com.prompthub.user.auth.presentation.dto.response;

import com.prompthub.user.auth.application.dto.TokenRefreshResult;

import java.time.Instant;

public record TokenRefreshResponse(String accessToken, Instant expiresAt) {

    public static TokenRefreshResponse from(TokenRefreshResult result) {
        return new TokenRefreshResponse(result.accessToken(), result.expiresAt());
    }
}
