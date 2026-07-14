package com.prompthub.user.auth.domain.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "refresh_token")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    private UUID refreshTokenId;

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    private UUID userId;

    @Column(name = "token", nullable = false, columnDefinition = "text")
    private String token;

    @Column(name = "epoch", nullable = false)
    private long epoch;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public static RefreshToken create(UUID userId, String token, Instant expiresAt) {
        RefreshToken refreshToken = new RefreshToken();
        refreshToken.refreshTokenId = UUID.randomUUID();
        refreshToken.userId = userId;
        refreshToken.token = token;
        refreshToken.epoch = 0;
        refreshToken.expiresAt = expiresAt;
        return refreshToken;
    }

    public static RefreshToken reconstruct(UUID refreshTokenId, UUID userId, String token, long epoch, Instant expiresAt) {
        RefreshToken refreshToken = new RefreshToken();
        refreshToken.refreshTokenId = refreshTokenId;
        refreshToken.userId = userId;
        refreshToken.token = token;
        refreshToken.epoch = epoch;
        refreshToken.expiresAt = expiresAt;
        return refreshToken;
    }

    public void rotate(String newToken, Instant newExpiresAt) {
        this.token = newToken;
        this.epoch += 1;
        this.expiresAt = newExpiresAt;
    }
}
