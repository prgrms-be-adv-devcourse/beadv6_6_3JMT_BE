package com.prompthub.user.auth.application.service;

import com.prompthub.user.auth.domain.repository.AuthorizationCacheRepository;
import com.prompthub.user.auth.domain.repository.RefreshTokenRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class SessionRevocationApplicationServiceTest {

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private AuthorizationCacheRepository authorizationCacheRepository;

    @InjectMocks
    private SessionRevocationApplicationService sessionRevocationService;

    @Test
    void revoke_유저의_모든_RT_삭제() {
        UUID userId = UUID.randomUUID();

        sessionRevocationService.revoke(userId);

        then(refreshTokenRepository).should().deleteByUserId(userId);
    }

    @Test
    void revoke_authorize_캐시_무효화() {
        UUID userId = UUID.randomUUID();

        sessionRevocationService.revoke(userId);

        then(authorizationCacheRepository).should().evict(userId);
    }
}
