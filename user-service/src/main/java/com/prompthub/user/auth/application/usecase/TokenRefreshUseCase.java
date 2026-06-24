package com.prompthub.user.auth.application.usecase;

import com.prompthub.user.auth.application.dto.TokenRefreshCommand;
import com.prompthub.user.auth.application.dto.TokenRefreshResult;

public interface TokenRefreshUseCase {

    TokenRefreshResult refresh(TokenRefreshCommand command);
}
