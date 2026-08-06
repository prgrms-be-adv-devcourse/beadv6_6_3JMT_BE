package com.prompthub.user.auth.presentation.dto.response;

import com.prompthub.user.auth.application.dto.TokenRefreshResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "토큰 재발급 응답")
public record TokenRefreshResponse(
        @Schema(description = "새로 발급된 JWT 액세스 토큰", example = "eyJhbGci...")
        String accessToken,
        @Schema(description = "액세스 토큰 만료일시 (ISO 8601)", example = "2025-06-17T11:00:00Z")
        Instant expiresAt,
        @Schema(description = "새로 발급된 JWT 리프레시 토큰(RTR)", example = "eyJhbGci...")
        String refreshToken
) {

    public static TokenRefreshResponse from(TokenRefreshResult result) {
        return new TokenRefreshResponse(result.accessToken(), result.expiresAt(), result.refreshToken());
    }
}
