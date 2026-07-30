package com.prompthub.user.auth.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.prompthub.user.auth.domain.model.RejoinToken;

@ExtendWith(MockitoExtension.class)
class RedisRejoinTokenAdapterTest {

    private static final UUID USER_ID = UUID.fromString("6fdcaf4a-300d-4bdd-88b9-9e7387654e06");
    private static final String TOKEN_HASH =
            "3c469e9d6c5875d37a43f353d4f88e61fcf812c66eee3457465a40b0da4153e0";
    private static final String TOKEN_KEY = "auth:rejoin:" + TOKEN_HASH;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RedisRejoinTokenAdapter adapter() {
        return new RedisRejoinTokenAdapter(redisTemplate);
    }

    @Test
    void create_32바이트_토큰을_해시키로_5분간_저장한다() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        Instant before = Instant.now().plus(Duration.ofMinutes(5));

        RejoinToken result = adapter().create(USER_ID);

        Instant after = Instant.now().plus(Duration.ofMinutes(5));
        assertThat(Base64.getUrlDecoder().decode(result.value())).hasSize(32);
        assertThat(result.expiresAt()).isBetween(before, after);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        then(valueOperations).should().set(
                keyCaptor.capture(),
                eq(USER_ID.toString()),
                eq(Duration.ofMinutes(5)));
        assertThat(keyCaptor.getValue()).matches("auth:rejoin:[0-9a-f]{64}");
        assertThat(keyCaptor.getValue()).doesNotContain(result.value());
    }

    @Test
    void consume_SHA256_해시키를_getAndDelete해_사용자를_반환한다() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.getAndDelete(TOKEN_KEY)).willReturn(USER_ID.toString());

        Optional<UUID> result = adapter().consume("token");

        assertThat(result).contains(USER_ID);
        then(valueOperations).should().getAndDelete(TOKEN_KEY);
    }

    @Test
    void consume_이미_소비된_토큰은_empty를_반환한다() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.getAndDelete(TOKEN_KEY)).willReturn(null);

        assertThat(adapter().consume("token")).isEmpty();
    }

    @Test
    void create_Redis_장애를_전파해_failClosed한다() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        willThrow(new RedisConnectionFailureException("연결 실패"))
                .given(valueOperations)
                .set(anyString(), anyString(), any(Duration.class));

        assertThatThrownBy(() -> adapter().create(USER_ID))
                .isInstanceOf(RedisConnectionFailureException.class);
    }
}
