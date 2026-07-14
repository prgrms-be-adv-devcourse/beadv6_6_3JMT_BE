package com.prompthub.user.auth.infrastructure.redis;

import com.prompthub.user.auth.domain.model.AuthzSnapshot;
import com.prompthub.user.user.domain.model.UserRole;
import com.prompthub.user.user.domain.model.UserStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

@ExtendWith(MockitoExtension.class)
class RedisAuthorizationCacheAdapterTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String KEY = "user:authz:" + USER_ID;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private HashOperations<String, String, String> hashOperations;

    private RedisAuthorizationCacheAdapter adapter() {
        return new RedisAuthorizationCacheAdapter(redisTemplate);
    }

    @Test
    void find_캐시_hit_시_AuthzSnapshot_반환() {
        given(redisTemplate.<String, String>opsForHash()).willReturn(hashOperations);
        given(hashOperations.entries(KEY)).willReturn(Map.of("status", "ACTIVE", "role", "BUYER"));

        Optional<AuthzSnapshot> result = adapter().find(USER_ID);

        assertThat(result).contains(new AuthzSnapshot(UserStatus.ACTIVE, UserRole.BUYER));
    }

    @Test
    void find_캐시_miss_시_empty_반환() {
        given(redisTemplate.<String, String>opsForHash()).willReturn(hashOperations);
        given(hashOperations.entries(KEY)).willReturn(Map.of());

        Optional<AuthzSnapshot> result = adapter().find(USER_ID);

        assertThat(result).isEmpty();
    }

    @Test
    void find_Redis_장애_시_예외를_삼키고_empty_반환() {
        given(redisTemplate.<String, String>opsForHash()).willReturn(hashOperations);
        given(hashOperations.entries(KEY)).willThrow(new RedisConnectionFailureException("연결 실패"));

        Optional<AuthzSnapshot> result = adapter().find(USER_ID);

        assertThat(result).isEmpty();
    }

    @Test
    void save_상태와_역할을_Hash로_저장하고_TTL_60초_설정() {
        given(redisTemplate.<String, String>opsForHash()).willReturn(hashOperations);

        adapter().save(USER_ID, new AuthzSnapshot(UserStatus.ACTIVE, UserRole.SELLER));

        then(hashOperations).should().putAll(KEY, Map.of("status", "ACTIVE", "role", "SELLER"));
        then(redisTemplate).should().expire(eq(KEY), eq(Duration.ofSeconds(60)));
    }

    @Test
    void save_Redis_장애_시_예외를_전파하지_않음() {
        given(redisTemplate.<String, String>opsForHash()).willReturn(hashOperations);
        willThrow(new RedisConnectionFailureException("연결 실패"))
                .given(hashOperations).putAll(eq(KEY), org.mockito.ArgumentMatchers.anyMap());

        assertThatCode(() -> adapter().save(USER_ID, new AuthzSnapshot(UserStatus.ACTIVE, UserRole.BUYER)))
                .doesNotThrowAnyException();
    }

    @Test
    void evict_키를_삭제한다() {
        adapter().evict(USER_ID);

        then(redisTemplate).should().delete(KEY);
    }

    @Test
    void evict_Redis_장애_시_예외를_전파하지_않음() {
        given(redisTemplate.delete(KEY)).willThrow(new RedisConnectionFailureException("연결 실패"));

        assertThatCode(() -> adapter().evict(USER_ID)).doesNotThrowAnyException();
    }
}
