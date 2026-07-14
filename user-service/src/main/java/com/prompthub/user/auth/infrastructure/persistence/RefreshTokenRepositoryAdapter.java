package com.prompthub.user.auth.infrastructure.persistence;

import com.prompthub.user.auth.domain.model.RefreshToken;
import com.prompthub.user.auth.domain.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
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
    // RT의 실제 만료(최대 7일)까지 캐시를 살려두면, Redis 삭제 실패(evictCache) 시
    // "무효화됐지만 캐시에는 살아있는" 세션이 그만큼 오래 남을 수 있다(Finding 2).
    // RDB가 항상 source of truth이므로 캐시는 짧게 두고 미스는 RDB 폴백으로 흡수한다.
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

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

    // 캐시를 거치지 않는다 — 재발급의 비교-후-회전(compare-and-rotate)은 동시 요청 간
    // 직렬화가 필요한 보안 크리티컬 구간이다. findByUserId처럼 캐시를 먼저 확인하면
    // 캐시 히트 시 JPA(및 그 위의 락)를 전혀 타지 않게 되어, 두 요청이 같은 캐시된
    // 값을 동시에 읽고 각자 회전 조건을 통과해버린다 — 한쪽이 커밋한 회전을 다른
    // 쪽이 덮어써도 예외 없이 조용히 사라지고, 뒤처진 클라이언트는 다음 재발급 때
    // 재사용(탈취)으로 오탐되어 세션이 파괴된다(최종 리뷰 발견, Finding 1).
    // 그래서 이 메서드는 RDB 행에 PESSIMISTIC_WRITE 락을 직접 걸어 동시 요청을
    // 진짜로 직렬화한다. 캐시 재적재도 여기서 하지 않는다 — 회전 완료 후 호출되는
    // save()가 이미 캐시를 갱신하므로 중복이다.
    @Override
    public Optional<RefreshToken> findByUserIdForUpdate(UUID userId) {
        return refreshTokenJpaRepository.findByUserIdForUpdate(userId);
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
            redisTemplate.expire(key, CACHE_TTL);
        } catch (DataAccessException e) {
            log.warn("Redis 캐시 갱신 실패 — RDB만 반영됩니다. userId={}", refreshToken.getUserId(), e);
        }
    }

    private void evictCache(UUID userId) {
        try {
            redisTemplate.delete(cacheKey(userId));
        } catch (DataAccessException e) {
            // RDB 삭제(세션 무효화)는 이미 끝났지만 캐시 삭제가 실패한 상태다.
            // 특히 재사용 감지로 인한 강제 무효화 직후라면, 캐시가 살아있는 동안
            // 이미 탈취된 RT가 "유효한 것처럼" 계속 통과할 수 있다 — 운영 경보가
            // 필요한 보안 이슈이므로 warn이 아니라 error로 남긴다.
            log.error("Redis 캐시 삭제 실패 — 세션 무효화가 캐시에는 반영되지 않았을 수 있음. userId={}", userId, e);
        }
    }

    private String cacheKey(UUID userId) {
        return CACHE_KEY_PREFIX + userId;
    }
}
