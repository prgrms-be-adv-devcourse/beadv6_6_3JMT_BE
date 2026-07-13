package com.prompthub.user.auth.presentation.dto.response;

import com.prompthub.user.auth.application.dto.AuthorizeResult;
import com.prompthub.user.user.domain.model.UserRole;
import com.prompthub.user.user.domain.model.UserStatus;

public record AuthorizeResponse(UserStatus status, UserRole role) {

    public static AuthorizeResponse from(AuthorizeResult result) {
        return new AuthorizeResponse(result.status(), result.role());
    }
}
