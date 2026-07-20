package com.prompthub.admin.auth.application.service;

import com.prompthub.admin.auth.domain.repository.AuthorizationCacheRepository;
import com.prompthub.admin.auth.domain.repository.RefreshTokenRepository;
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
	void revoke_유저의_모든_RT를_삭제한다() {
		UUID userId = UUID.randomUUID();

		sessionRevocationService.revoke(userId);

		then(refreshTokenRepository).should().deleteByUserId(userId);
	}

	@Test
	void revoke_authorize_캐시를_무효화한다() {
		UUID userId = UUID.randomUUID();

		sessionRevocationService.revoke(userId);

		then(authorizationCacheRepository).should().evict(userId);
	}
}
