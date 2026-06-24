package com.prompthub.user.auth.application.usecase;

import com.prompthub.user.auth.application.dto.OAuthLoginCommand;
import com.prompthub.user.auth.application.dto.OAuthLoginResult;

public interface OAuthUseCase {
    OAuthLoginResult login(OAuthLoginCommand command);
}
