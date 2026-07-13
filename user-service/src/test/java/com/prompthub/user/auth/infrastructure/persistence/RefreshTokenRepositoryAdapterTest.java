package com.prompthub.user.auth.infrastructure.persistence;

import com.prompthub.user.auth.domain.model.RefreshToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class RefreshTokenRepositoryAdapterTest {

    @Mock
    private RefreshTokenJpaRepository refreshTokenJpaRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final Instant EXPIRES_AT = Instant.parse("2026-07-20T00:00:00Z");

    private RefreshTokenRepositoryAdapter adapter() {
        return new RefreshTokenRepositoryAdapter(refreshTokenJpaRepository, redisTemplate);
    }

    @Test
    void save_RDB_먼저_저장_후_Redis_캐시_갱신() {
        RefreshToken refreshToken = RefreshToken.create(USER_ID, "token", EXPIRES_AT);
        given(refreshTokenJpaRepository.save(refreshToken)).willReturn(refreshToken);
        given(redisTemplate.opsForHash()).willReturn(hashOperations);

        RefreshToken result = adapter().save(refreshToken);

        assertThat(result).isEqualTo(refreshToken);
        then(refreshTokenJpaRepository).should().save(refreshToken);
        then(hashOperations).should().put(eq("refresh_token:" + USER_ID), eq("token"), eq("token"));
    }

    @Test
    void findByUserId_캐시_히트면_RDB_조회하지_않는다() {
        given(redisTemplate.opsForHash()).willReturn(hashOperations);
        UUID cachedId = UUID.randomUUID();
        Map<Object, Object> cached = Map.of(
                "id", cachedId.toString(),
                "token", "cached-token",
                "epoch", "2",
                "expiresAt", String.valueOf(EXPIRES_AT.toEpochMilli())
        );
        given(hashOperations.entries("refresh_token:" + USER_ID)).willReturn(cached);

        Optional<RefreshToken> result = adapter().findByUserId(USER_ID);

        assertThat(result).isPresent();
        assertThat(result.get().getToken()).isEqualTo("cached-token");
        assertThat(result.get().getEpoch()).isEqualTo(2L);
        then(refreshTokenJpaRepository).shouldHaveNoInteractions();
    }

    @Test
    void findByUserId_캐시_미스면_RDB_조회_후_재적재() {
        given(redisTemplate.opsForHash()).willReturn(hashOperations);
        given(hashOperations.entries("refresh_token:" + USER_ID)).willReturn(Map.of());
        RefreshToken stored = RefreshToken.create(USER_ID, "db-token", EXPIRES_AT);
        given(refreshTokenJpaRepository.findByUserId(USER_ID)).willReturn(Optional.of(stored));

        Optional<RefreshToken> result = adapter().findByUserId(USER_ID);

        assertThat(result).contains(stored);
        then(hashOperations).should().put(eq("refresh_token:" + USER_ID), eq("token"), eq("db-token"));
    }

    @Test
    void findByUserId_Redis_장애면_RDB로_폴백() {
        given(redisTemplate.opsForHash()).willThrow(new RedisConnectionFailureException("connection refused"));
        RefreshToken stored = RefreshToken.create(USER_ID, "db-token", EXPIRES_AT);
        given(refreshTokenJpaRepository.findByUserId(USER_ID)).willReturn(Optional.of(stored));

        Optional<RefreshToken> result = adapter().findByUserId(USER_ID);

        assertThat(result).contains(stored);
    }

    @Test
    void deleteByUserId_RDB와_Redis_모두_삭제() {
        adapter().deleteByUserId(USER_ID);

        then(refreshTokenJpaRepository).should().deleteByUserId(USER_ID);
        then(redisTemplate).should().delete("refresh_token:" + USER_ID);
    }
}
