package com.prompthub.admin.auth.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

@ExtendWith(MockitoExtension.class)
class AuthorizationCacheRepositoryTest {

	private static final UUID USER_ID = UUID.randomUUID();
	private static final String KEY = "user:authz:" + USER_ID;

	@Mock
	private StringRedisTemplate redisTemplate;

	private AuthorizationCacheRepository adapter() {
		return new AuthorizationCacheRepository(redisTemplate);
	}

	@Test
	void evict_해당_유저의_인가_캐시_키를_지운다() {
		adapter().evict(USER_ID);

		then(redisTemplate).should().delete(KEY);
	}

	@Test
	void evict_Redis_장애면_예외를_삼키고_로그만_남긴다() {
		willThrow(new RedisConnectionFailureException("connection refused"))
			.given(redisTemplate).delete(KEY);

		assertThatCode(() -> adapter().evict(USER_ID)).doesNotThrowAnyException();
	}
}
