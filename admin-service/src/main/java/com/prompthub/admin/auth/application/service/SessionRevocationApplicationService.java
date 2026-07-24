package com.prompthub.admin.auth.application.service;

import com.prompthub.admin.auth.infrastructure.persistence.RefreshTokenRepository;
import com.prompthub.admin.auth.infrastructure.redis.AuthorizationCacheRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SessionRevocationApplicationService {

	private final RefreshTokenRepository refreshTokenRepository;
	private final AuthorizationCacheRepository authorizationCacheRepository;

	@Transactional
	public void revoke(UUID userId) {
		refreshTokenRepository.deleteByUserId(userId);
		authorizationCacheRepository.evict(userId);
	}

	public void evictAuthorizationCache(UUID userId) {
		authorizationCacheRepository.evict(userId);
	}
}
