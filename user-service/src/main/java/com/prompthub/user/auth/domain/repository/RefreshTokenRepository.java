package com.prompthub.user.auth.domain.repository;

import com.prompthub.user.auth.domain.model.RefreshToken;

import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository {
    RefreshToken save(RefreshToken refreshToken);
    Optional<RefreshToken> findByUserId(UUID userId);
    void deleteByUserId(UUID userId);
}
