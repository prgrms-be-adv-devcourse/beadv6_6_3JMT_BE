package com.prompthub.user.auth.application.usecase;

import java.util.UUID;

public interface SessionRevocationUseCase {
    void revoke(UUID userId);
}
