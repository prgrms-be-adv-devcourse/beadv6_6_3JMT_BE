package com.prompthub.user.auth.presentation.dto.request;

import com.prompthub.user.auth.application.dto.OAuthLoginCommand;
import com.prompthub.user.auth.domain.model.OAuthProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "OAuth 소셜 로그인 요청")
public record OAuthLoginRequest(
        @Schema(description = "OAuth 제공자로부터 발급받은 access token", example = "abcdEFGH1234...")
        @NotBlank String accessToken
) {
    public OAuthLoginCommand toCommand(OAuthProvider provider) {
        return new OAuthLoginCommand(provider, accessToken);
    }
}
