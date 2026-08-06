package com.prompthub.user.auth.infrastructure.redis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.prompthub.user.auth.domain.model.RejoinToken;
import com.prompthub.user.auth.domain.repository.RejoinTokenRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class RedisRejoinTokenAdapter implements RejoinTokenRepository {

    private static final String KEY_PREFIX = "auth:rejoin:";
    private static final int TOKEN_BYTES = 32;
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final StringRedisTemplate redisTemplate;

    @Override
    public RejoinToken create(UUID userId) {
        byte[] tokenBytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(tokenBytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        Instant expiresAt = Instant.now().plus(TTL);

        redisTemplate.opsForValue().set(key(rawToken), userId.toString(), TTL);

        return new RejoinToken(rawToken, expiresAt);
    }

    @Override
    public Optional<UUID> consume(String rawToken) {
        String userId = redisTemplate.opsForValue().getAndDelete(key(rawToken));
        return Optional.ofNullable(userId).map(UUID::fromString);
    }

    private String key(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return KEY_PREFIX + HexFormat.of().formatHex(
                    digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", exception);
        }
    }
}
