package com.prompthub.admin.auth.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * user-service 소유 인가 캐시(Redis, 키 "user:authz:{userId}", 60초 TTL)의
 * 무효화 전용. gateway forward-auth가 이 캐시를 읽으므로, 어드민이
 * 회원 상태·역할을 바꾸면 여기서 evict해야 최대 60초짜리 stale 인가가 안 생긴다.
 * 캐시 적재/조회(find/save)는 로그인 경로 전용이라 admin-service엔 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthorizationCacheRepository {

	private static final String KEY_PREFIX = "user:authz:";

	private final StringRedisTemplate redisTemplate;

	public void evict(UUID userId) {
		try {
			redisTemplate.delete(KEY_PREFIX + userId);
		} catch (DataAccessException e) {
			log.warn("authorize 캐시 무효화 실패. userId={}", userId, e);
		}
	}
}
