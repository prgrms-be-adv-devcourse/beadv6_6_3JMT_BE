package com.prompthub.user.auth.infrastructure.redis;

import com.prompthub.user.auth.domain.model.AuthzSnapshot;
import com.prompthub.user.auth.domain.repository.AuthorizationCacheRepository;
import com.prompthub.user.user.domain.model.UserRole;
import com.prompthub.user.user.domain.model.UserStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisAuthorizationCacheAdapter implements AuthorizationCacheRepository {

    private static final String KEY_PREFIX = "user:authz:";
    private static final Duration TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate redisTemplate;

    @Override
    public Optional<AuthzSnapshot> find(UUID userId) {
        try {
            Map<String, String> entries = redisTemplate.<String, String>opsForHash().entries(key(userId));
            if (entries.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new AuthzSnapshot(
                    UserStatus.valueOf(entries.get("status")),
                    UserRole.valueOf(entries.get("role"))
            ));
        } catch (DataAccessException e) {
            log.warn("authorize 캐시 조회 실패 — DB 폴백. userId={}", userId, e);
            return Optional.empty();
        }
    }

    @Override
    public void save(UUID userId, AuthzSnapshot snapshot) {
        try {
            String key = key(userId);
            redisTemplate.<String, String>opsForHash().putAll(key, Map.of(
                    "status", snapshot.status().name(),
                    "role", snapshot.role().name()
            ));
            redisTemplate.expire(key, TTL);
        } catch (DataAccessException e) {
            log.warn("authorize 캐시 적재 실패. userId={}", userId, e);
        }
    }

    @Override
    public void evict(UUID userId) {
        try {
            redisTemplate.delete(key(userId));
        } catch (DataAccessException e) {
            log.warn("authorize 캐시 무효화 실패. userId={}", userId, e);
        }
    }

    private static String key(UUID userId) {
        return KEY_PREFIX + userId;
    }
}
