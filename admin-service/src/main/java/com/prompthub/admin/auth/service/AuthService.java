package com.prompthub.admin.auth.service;

import com.prompthub.admin.auth.repository.RefreshTokenRepository;
import com.prompthub.admin.auth.repository.AuthorizationCacheRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

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
