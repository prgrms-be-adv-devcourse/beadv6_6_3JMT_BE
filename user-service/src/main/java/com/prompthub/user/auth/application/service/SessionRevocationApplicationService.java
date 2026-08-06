package com.prompthub.user.auth.application.service;

import com.prompthub.user.auth.application.usecase.SessionRevocationUseCase;
import com.prompthub.user.auth.domain.repository.AuthorizationCacheRepository;
import com.prompthub.user.auth.domain.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SessionRevocationApplicationService implements SessionRevocationUseCase {

    private final RefreshTokenRepository refreshTokenRepository;
    private final AuthorizationCacheRepository authorizationCacheRepository;

    @Override
    @Transactional
    public void revoke(UUID userId) {
        refreshTokenRepository.deleteByUserId(userId);
        authorizationCacheRepository.evict(userId);
    }
}
