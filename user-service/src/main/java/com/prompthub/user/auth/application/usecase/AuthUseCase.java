package com.prompthub.user.auth.application.usecase;

import com.prompthub.user.auth.application.dto.OAuthLoginCommand;
import com.prompthub.user.auth.application.dto.OAuthLoginResult;
import com.prompthub.user.auth.application.dto.TokenRefreshCommand;
import com.prompthub.user.auth.application.dto.TokenRefreshResult;

import java.util.UUID;

public interface AuthUseCase {
    OAuthLoginResult oAuthLogin(OAuthLoginCommand command);
    TokenRefreshResult refresh(TokenRefreshCommand command);
    void logout(UUID userId);
}
