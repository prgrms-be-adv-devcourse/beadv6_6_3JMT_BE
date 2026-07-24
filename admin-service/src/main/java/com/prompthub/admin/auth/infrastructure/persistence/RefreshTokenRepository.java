package com.prompthub.admin.auth.infrastructure.persistence;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenRepository {

	private static final String CACHE_KEY_PREFIX = "refresh_token:";

	private final RefreshTokenJpaRepository refreshTokenJpaRepository;
	private final StringRedisTemplate redisTemplate;

	public void deleteByUserId(UUID userId) {
		refreshTokenJpaRepository.deleteByUserId(userId);
		evictCache(userId);
	}

	private void evictCache(UUID userId) {
		try {
			redisTemplate.delete(CACHE_KEY_PREFIX + userId);
		} catch (DataAccessException e) {
			// RDB 삭제(세션 무효화)는 끝났지만 캐시 삭제가 실패한 상태 — user-service
			// 원본과 동일하게 보안 경보용 error로 남긴다(warn 아님).
			log.error("Redis 캐시 삭제 실패 — 세션 무효화가 캐시에는 반영되지 않았을 수 있음. userId={}", userId, e);
		}
	}
}
