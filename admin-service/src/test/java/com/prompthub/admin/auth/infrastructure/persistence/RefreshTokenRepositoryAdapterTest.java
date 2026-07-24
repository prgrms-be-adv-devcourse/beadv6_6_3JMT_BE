package com.prompthub.admin.auth.infrastructure.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class RefreshTokenRepositoryAdapterTest {

	@Mock
	private RefreshTokenJpaRepository refreshTokenJpaRepository;

	@Mock
	private StringRedisTemplate redisTemplate;

	private RefreshTokenRepository adapter() {
		return new RefreshTokenRepository(refreshTokenJpaRepository, redisTemplate);
	}

	@Test
	void deleteByUserId_RDB와_Redis_모두_삭제() {
		UUID userId = UUID.randomUUID();

		adapter().deleteByUserId(userId);

		then(refreshTokenJpaRepository).should().deleteByUserId(userId);
		then(redisTemplate).should().delete("refresh_token:" + userId);
	}
}
