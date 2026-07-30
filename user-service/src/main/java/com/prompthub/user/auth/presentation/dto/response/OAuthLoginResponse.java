package com.prompthub.user.auth.presentation.dto.response;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.prompthub.user.auth.application.dto.OAuthLoginCompletedResult;
import com.prompthub.user.auth.application.dto.OAuthLoginResult;
import com.prompthub.user.auth.application.dto.OAuthLoginStatus;
import com.prompthub.user.auth.application.dto.OAuthRejoinRequiredResult;
import com.prompthub.user.user.domain.model.UserRole;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
        description = "OAuth 로그인 처리 결과",
        oneOf = {
                OAuthLoginResponse.Completed.class,
                OAuthLoginResponse.RejoinRequired.class
        }
)
public sealed interface OAuthLoginResponse
        permits OAuthLoginResponse.Completed, OAuthLoginResponse.RejoinRequired {

    OAuthLoginStatus loginStatus();

    boolean isNewUser();

    static OAuthLoginResponse from(OAuthLoginResult result) {
        return switch (result) {
            case OAuthLoginCompletedResult completed -> new Completed(
                    completed.loginStatus(),
                    new UserInfo(
                            completed.userId(),
                            completed.name(),
                            completed.email(),
                            completed.roles()),
                    completed.accessToken(),
                    completed.refreshToken(),
                    completed.tokenType(),
                    completed.expiresAt(),
                    completed.isNewUser()
            );
            case OAuthRejoinRequiredResult rejoinRequired -> new RejoinRequired(
                    rejoinRequired.loginStatus(),
                    rejoinRequired.isNewUser(),
                    rejoinRequired.rejoinToken(),
                    rejoinRequired.rejoinExpiresAt()
            );
        };
    }

    @Schema(description = "로그인이 완료되어 서비스 토큰이 발급된 응답")
    record Completed(
            @Schema(description = "로그인 처리 상태", example = "COMPLETED")
            OAuthLoginStatus loginStatus,
            @Schema(description = "사용자 정보")
            UserInfo user,
            @Schema(description = "JWT 액세스 토큰", example = "eyJhbGci...")
            String accessToken,
            @Schema(description = "JWT 리프레시 토큰", example = "eyJhbGci...")
            String refreshToken,
            @Schema(description = "토큰 타입", example = "Bearer")
            String tokenType,
            @Schema(description = "액세스 토큰 만료일시", example = "2026-07-30T12:15:00Z")
            Instant expiresAt,
            @Schema(description = "신규 가입 여부", example = "false")
            boolean isNewUser
    ) implements OAuthLoginResponse {
    }

    @Schema(description = "탈퇴 계정으로 재가입 확인이 필요한 응답")
    record RejoinRequired(
            @Schema(description = "로그인 처리 상태", example = "REJOIN_REQUIRED")
            OAuthLoginStatus loginStatus,
            @Schema(description = "신규 가입 여부", example = "false")
            boolean isNewUser,
            @Schema(description = "일회성 재가입 확인 토큰")
            String rejoinToken,
            @Schema(description = "재가입 확인 토큰 만료일시", example = "2026-07-30T12:05:00Z")
            Instant rejoinExpiresAt
    ) implements OAuthLoginResponse {
    }

    @Schema(description = "사용자 기본 정보")
    record UserInfo(
            @Schema(description = "사용자 ID") UUID id,
            @Schema(description = "이름", example = "카카오사용자") String name,
            @Schema(description = "이메일", example = "kakao@user.com") String email,
            @Schema(description = "역할 목록", example = "[\"BUYER\"]") Set<UserRole> roles
    ) {
    }
}
