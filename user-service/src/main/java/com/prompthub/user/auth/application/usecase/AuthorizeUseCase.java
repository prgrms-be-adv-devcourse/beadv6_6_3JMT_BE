package com.prompthub.user.auth.application.usecase;

import com.prompthub.user.auth.application.dto.AuthorizeResult;

import java.util.UUID;

public interface AuthorizeUseCase {
    AuthorizeResult authorize(UUID userId);
}
