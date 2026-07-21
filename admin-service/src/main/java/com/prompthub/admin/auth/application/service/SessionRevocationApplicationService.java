package com.prompthub.admin.auth.application.service;

import com.prompthub.admin.auth.application.usecase.SessionRevocationUseCase;
import com.prompthub.admin.auth.domain.repository.AuthorizationCacheRepository;
import com.prompthub.admin.auth.domain.repository.RefreshTokenRepository;
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
