package com.prompthub.admin.auth.service;

import com.prompthub.admin.auth.repository.RefreshTokenRepository;
import com.prompthub.admin.auth.repository.AuthorizationCacheRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

	@Mock
	private RefreshTokenRepository refreshTokenRepository;

	@Mock
	private AuthorizationCacheRepository authorizationCacheRepository;

	@InjectMocks
	private AuthService sessionRevocationService;

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

	@Test
	void evictAuthorizationCache_RT는_건드리지_않고_인가캐시만_무효화한다() {
		UUID userId = UUID.randomUUID();

		sessionRevocationService.evictAuthorizationCache(userId);

		then(authorizationCacheRepository).should().evict(userId);
		then(refreshTokenRepository).shouldHaveNoInteractions();
	}
}
