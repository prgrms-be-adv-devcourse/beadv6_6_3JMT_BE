package com.prompthub.user.auth.presentation.dto.request;

import com.prompthub.user.auth.application.dto.OAuthLoginCommand;
import com.prompthub.user.auth.domain.model.OAuthProvider;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record OAuthLoginRequest(
        @NotBlank String providerUserId,
        @NotBlank String nickname,
        String profileImage,
        @NotBlank @Email String email
) {
    public OAuthLoginCommand toCommand(OAuthProvider provider) {
        return new OAuthLoginCommand(provider, providerUserId, nickname, profileImage, email);
    }
}
