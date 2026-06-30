package com.prompthub.user.auth.presentation.dto.request;

import com.prompthub.user.auth.application.dto.OAuthLoginCommand;
import com.prompthub.user.auth.domain.model.OAuthProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "OAuth 소셜 로그인 요청")
public record OAuthLoginRequest(
        @Schema(description = "OAuth 제공자의 고유 식별자", example = "123456789")
        @NotBlank String oauthId,
        @Schema(description = "닉네임", example = "카카오사용자")
        @NotBlank String name,
        @Schema(description = "프로필 이미지 URL (없으면 null)", nullable = true)
        String profileImage,
        @Schema(description = "이메일", example = "kakao@user.com")
        @NotBlank @Email String email
) {
    public OAuthLoginCommand toCommand(OAuthProvider provider) {
        return new OAuthLoginCommand(provider, oauthId, name, profileImage, email);
    }
}
