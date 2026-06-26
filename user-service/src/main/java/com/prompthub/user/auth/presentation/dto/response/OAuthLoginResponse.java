package com.prompthub.user.auth.presentation.dto.response;

import com.prompthub.user.auth.application.dto.OAuthLoginResult;
import com.prompthub.user.user.domain.model.UserRole;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Schema(description = "OAuth 소셜 로그인 응답")
public record OAuthLoginResponse(
        @Schema(description = "사용자 정보")
        UserInfo user,
        @Schema(description = "JWT 액세스 토큰", example = "eyJhbGci...")
        String accessToken,
        @Schema(description = "JWT 리프레시 토큰", example = "eyJhbGci...")
        String refreshToken,
        @Schema(description = "토큰 타입", example = "Bearer")
        String tokenType,
        @Schema(description = "액세스 토큰 만료일시 (ISO 8601)", example = "2025-06-17T11:00:00Z")
        Instant expiresAt,
        @Schema(description = "신규 가입 여부", example = "false")
        boolean isNewUser
) {
    @Schema(description = "사용자 기본 정보")
    public record UserInfo(
            @Schema(description = "사용자 ID") UUID id,
            @Schema(description = "이름", example = "카카오사용자") String name,
            @Schema(description = "이메일", example = "kakao@user.com") String email,
            @Schema(description = "역할 목록", example = "[\"BUYER\"]") Set<UserRole> roles
    ) {}

    public static OAuthLoginResponse from(OAuthLoginResult result) {
        return new OAuthLoginResponse(
                new UserInfo(result.userId(), result.name(), result.email(), result.roles()),
                result.accessToken(),
                result.refreshToken(),
                result.tokenType(),
                result.expiresAt(),
                result.isNewUser()
        );
    }
}
