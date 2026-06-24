package com.prompthub.user.auth.presentation.dto.request;

import com.prompthub.user.auth.application.dto.TokenRefreshCommand;
import jakarta.validation.constraints.NotBlank;

public record TokenRefreshRequest(@NotBlank String refreshToken) {

    public TokenRefreshCommand toCommand() {
        return new TokenRefreshCommand(refreshToken);
    }
}
