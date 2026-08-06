package com.prompthub.user.auth.application.dto;

import com.prompthub.user.auth.domain.model.AuthzSnapshot;
import com.prompthub.user.user.domain.model.UserRole;
import com.prompthub.user.user.domain.model.UserStatus;

public record AuthorizeResult(UserStatus status, UserRole role) {

    public static AuthorizeResult from(AuthzSnapshot snapshot) {
        return new AuthorizeResult(snapshot.status(), snapshot.role());
    }
}
