package com.prompthub.user.auth.presentation.dto.request;

import com.prompthub.user.auth.application.dto.TokenRefreshCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "토큰 재발급 요청")
public record TokenRefreshRequest(
        @Schema(description = "JWT Refresh Token", example = "eyJhbGci...")
        @NotBlank String refreshToken
) {

    public TokenRefreshCommand toCommand() {
        return new TokenRefreshCommand(refreshToken);
    }
}
