package com.prompthub.user.auth.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_token")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {

    @Id
    @Column(name = "refresh_token_id", columnDefinition = "uuid")
    private UUID refreshTokenId;

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    private UUID userId;

    @Column(name = "token", nullable = false, columnDefinition = "text")
    private String token;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public static RefreshToken create(UUID userId, String token, Instant expiresAt) {
        RefreshToken refreshToken = new RefreshToken();
        refreshToken.refreshTokenId = UUID.randomUUID();
        refreshToken.userId = userId;
        refreshToken.token = token;
        refreshToken.expiresAt = expiresAt;
        return refreshToken;
    }
}
