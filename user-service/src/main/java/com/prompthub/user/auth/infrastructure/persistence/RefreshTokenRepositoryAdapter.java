package com.prompthub.user.auth.infrastructure.persistence;

import com.prompthub.user.auth.domain.model.RefreshToken;
import com.prompthub.user.auth.domain.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenRepositoryAdapter implements RefreshTokenRepository {

    private static final String CACHE_KEY_PREFIX = "refresh_token:";
    private static final String FIELD_ID = "id";
    private static final String FIELD_TOKEN = "token";
    private static final String FIELD_EPOCH = "epoch";
    private static final String FIELD_EXPIRES_AT = "expiresAt";

    private final RefreshTokenJpaRepository refreshTokenJpaRepository;
    private final StringRedisTemplate redisTemplate;

    @Override
    public RefreshToken save(RefreshToken refreshToken) {
        RefreshToken saved = refreshTokenJpaRepository.save(refreshToken);
        cache(saved);
        return saved;
    }

    @Override
    public Optional<RefreshToken> findByUserId(UUID userId) {
        Optional<RefreshToken> cached = findInCache(userId);
        if (cached.isPresent()) {
            return cached;
        }

        Optional<RefreshToken> found = refreshTokenJpaRepository.findByUserId(userId);
        found.ifPresent(this::cache);
        return found;
    }

    @Override
    public void deleteByUserId(UUID userId) {
        refreshTokenJpaRepository.deleteByUserId(userId);
        evictCache(userId);
    }

    private Optional<RefreshToken> findInCache(UUID userId) {
        try {
            HashOperations<String, Object, Object> hashOps = redisTemplate.opsForHash();
            Map<Object, Object> cached = hashOps.entries(cacheKey(userId));
            if (cached.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(RefreshToken.reconstruct(
                    UUID.fromString((String) cached.get(FIELD_ID)),
                    userId,
                    (String) cached.get(FIELD_TOKEN),
                    Long.parseLong((String) cached.get(FIELD_EPOCH)),
                    Instant.ofEpochMilli(Long.parseLong((String) cached.get(FIELD_EXPIRES_AT)))
            ));
        } catch (DataAccessException e) {
            log.warn("Redis 조회 실패로 RDB로 폴백합니다. userId={}", userId, e);
            return Optional.empty();
        }
    }

    private void cache(RefreshToken refreshToken) {
        try {
            String key = cacheKey(refreshToken.getUserId());
            HashOperations<String, Object, Object> hashOps = redisTemplate.opsForHash();
            hashOps.put(key, FIELD_ID, refreshToken.getRefreshTokenId().toString());
            hashOps.put(key, FIELD_TOKEN, refreshToken.getToken());
            hashOps.put(key, FIELD_EPOCH, String.valueOf(refreshToken.getEpoch()));
            hashOps.put(key, FIELD_EXPIRES_AT, String.valueOf(refreshToken.getExpiresAt().toEpochMilli()));
            redisTemplate.expireAt(key, Date.from(refreshToken.getExpiresAt()));
        } catch (DataAccessException e) {
            log.warn("Redis 캐시 갱신 실패 — RDB만 반영됩니다. userId={}", refreshToken.getUserId(), e);
        }
    }

    private void evictCache(UUID userId) {
        try {
            redisTemplate.delete(cacheKey(userId));
        } catch (DataAccessException e) {
            log.warn("Redis 캐시 삭제 실패. userId={}", userId, e);
        }
    }

    private String cacheKey(UUID userId) {
        return CACHE_KEY_PREFIX + userId;
    }
}
